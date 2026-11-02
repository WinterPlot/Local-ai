// LocalAI JNI layer over llama.cpp (pinned snapshot in ./llama.cpp).
//
// Threading model: all calls except nativeCancel() must be serialised by the
// caller (the Kotlin side uses one dedicated thread). nativeCancel() only
// touches an atomic flag and may be called from any thread.
//
// Strings crossing JNI are UTF-8 byte arrays, never jstring: JNI's "modified
// UTF-8" corrupts supplementary characters (emoji) in prompts and replies.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>
#include <unistd.h>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "logging.h"

namespace {

enum StopReason : int {
    STOP_NONE      = 0,
    STOP_EOG       = 1,  // model emitted an end-of-generation token
    STOP_MAX       = 2,  // max new tokens reached
    STOP_CTX_FULL  = 3,  // context window exhausted
    STOP_CANCELLED = 4,
    STOP_ERROR     = 5,
};

// Error codes returned to Kotlin (negative = failure).
constexpr int ERR_NO_MODEL     = -1;
constexpr int ERR_NO_CONTEXT   = -2;
constexpr int ERR_PROMPT_LONG  = -3;
constexpr int ERR_TEMPLATE     = -4;
constexpr int ERR_DECODE       = -5;
constexpr int ERR_CANCELLED    = -6;
constexpr int ERR_LOAD_FAILED  = -7;
constexpr int ERR_CTX_FAILED   = -8;
constexpr int ERR_BAD_ARGS     = -9;

struct Session {
    llama_model   *model   = nullptr;
    llama_context *ctx     = nullptr;
    llama_batch    batch{};
    bool           batch_ready = false;
    int            n_batch = 512;

    common_chat_templates_ptr templates;
    bool template_explicit = false;

    llama_sampler *sampler = nullptr;

    // Tokens currently materialised in the KV cache (for prefix reuse).
    std::vector<llama_token> cached_tokens;

    // Current generation.
    int  n_past       = 0;
    int  n_generated  = 0;
    int  max_new      = 0;
    int  stop_reason  = STOP_NONE;
    std::string pending_bytes;  // incomplete UTF-8 tail

    // Timing (microseconds).
    int64_t t_prompt_us = 0;
    int64_t t_gen_us    = 0;
    int     n_prompt_eval = 0;   // tokens actually decoded (after prefix reuse)
    int     n_prompt_total = 0;  // tokens in the prompt
    int     n_prompt_reused = 0;
    int64_t t_load_ms   = 0;
};

Session g;
std::mutex g_mutex;
std::atomic<bool> g_cancel{false};

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

bool abort_cb(void * /*data*/) { return g_cancel.load(std::memory_order_relaxed); }

std::string to_std(JNIEnv *env, jbyteArray arr) {
    if (!arr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jbyteArray to_java(JNIEnv *env, const std::string &s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out && !s.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()),
                                reinterpret_cast<const jbyte *>(s.data()));
    }
    return out;
}

// Length of the longest prefix of `s` that ends on a UTF-8 character boundary
// (i.e. does not cut a multi-byte sequence in half).
size_t complete_utf8_prefix(const std::string &s) {
    const size_t n = s.size();
    if (n == 0) return 0;
    // Walk back over at most 3 continuation bytes to find the lead byte.
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }  // continuation
        size_t need = 1;
        if      ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else return n;  // invalid lead byte: do not hold data back forever
        const size_t have = n - (i - 1);
        return have >= need ? n : i - 1;
    }
    return n;  // only continuation bytes: invalid, flush
}

std::string json_escape(const std::string &s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n";  break;
            case '\r': o += "\\r";  break;
            case '\t': o += "\\t";  break;
            default:
                if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
                else o += static_cast<char>(c);
        }
    }
    return o;
}

void free_sampler() {
    if (g.sampler) { llama_sampler_free(g.sampler); g.sampler = nullptr; }
}

void free_context() {
    free_sampler();
    if (g.batch_ready) { llama_batch_free(g.batch); g.batch_ready = false; }
    g.templates.reset();
    if (g.ctx) { llama_free(g.ctx); g.ctx = nullptr; }
    g.cached_tokens.clear();
    g.n_past = 0;
    g.stop_reason = STOP_NONE;
    g.pending_bytes.clear();
}

void free_model() {
    free_context();
    if (g.model) { llama_model_free(g.model); g.model = nullptr; }
}

std::string meta_str(const llama_model *m, const char *key) {
    char buf[512];
    const int32_t n = llama_model_meta_val_str(m, key, buf, sizeof buf);
    return n >= 0 ? std::string(buf) : std::string();
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_app_localai_engine_NativeBridge_nInit(JNIEnv *env, jobject, jstring jlib_dir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char *dir = env->GetStringUTFChars(jlib_dir, nullptr);
    LOGi("Loading ggml backends from native library dir");
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(jlib_dir, dir);
    llama_backend_init();
}

JNIEXPORT void JNICALL
Java_app_localai_engine_NativeBridge_nShutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model();
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_app_localai_engine_NativeBridge_nSystemInfo(JNIEnv *env, jobject) {
    // llama_print_system_info() is plain ASCII.
    return env->NewStringUTF(llama_print_system_info());
}

// Returns 0 on success or ERR_*.
JNIEXPORT jint JNICALL
Java_app_localai_engine_NativeBridge_nLoadModel(JNIEnv *env, jobject, jstring jpath, jboolean use_mmap) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model();
    g_cancel = false;

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = use_mmap == JNI_TRUE;
    mp.n_gpu_layers = 0;  // CPU only: no GPU backend is built into this app.

    const int64_t t0 = now_us();
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return ERR_LOAD_FAILED;

    g.model = model;
    g.t_load_ms = (now_us() - t0) / 1000;
    return 0;
}

// JSON with facts the runtime itself reports. Keys absent from the model are omitted.
JNIEXPORT jbyteArray JNICALL
Java_app_localai_engine_NativeBridge_nModelInfo(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g.model) return nullptr;

    char desc[256] = {0};
    llama_model_desc(g.model, desc, sizeof desc);
    const char *tmpl = llama_model_chat_template(g.model, nullptr);

    std::ostringstream o;
    o << "{";
    o << "\"desc\":\"" << json_escape(desc) << "\"";
    o << ",\"arch\":\"" << json_escape(meta_str(g.model, "general.architecture")) << "\"";
    o << ",\"name\":\"" << json_escape(meta_str(g.model, "general.name")) << "\"";
    o << ",\"sizeBytes\":" << llama_model_size(g.model);
    o << ",\"nParams\":" << llama_model_n_params(g.model);
    o << ",\"nCtxTrain\":" << llama_model_n_ctx_train(g.model);
    o << ",\"nEmbd\":" << llama_model_n_embd(g.model);
    o << ",\"nLayer\":" << llama_model_n_layer(g.model);
    o << ",\"nHead\":" << llama_model_n_head(g.model);
    o << ",\"nHeadKv\":" << llama_model_n_head_kv(g.model);
    o << ",\"hasChatTemplate\":" << (tmpl ? "true" : "false");
    o << ",\"loadMs\":" << g.t_load_ms;
    o << "}";
    return to_java(env, o.str());
}

JNIEXPORT jint JNICALL
Java_app_localai_engine_NativeBridge_nCreateContext(JNIEnv *, jobject, jint n_ctx, jint n_threads, jint n_batch) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g.model) return ERR_NO_MODEL;
    if (n_ctx < 128 || n_threads < 1 || n_batch < 8) return ERR_BAD_ARGS;
    free_context();

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(n_ctx);
    cp.n_batch         = static_cast<uint32_t>(n_batch);
    cp.n_ubatch        = static_cast<uint32_t>(std::min(n_batch, 512));
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;

    g.ctx = llama_init_from_model(g.model, cp);
    if (!g.ctx) return ERR_CTX_FAILED;

    g.n_batch = n_batch;
    g.batch = llama_batch_init(n_batch, 0, 1);
    g.batch_ready = true;
    g.templates = common_chat_templates_init(g.model, "");
    g.template_explicit = common_chat_templates_was_explicit(g.templates.get());
    llama_set_abort_callback(g.ctx, abort_cb, nullptr);
    return 0;
}

JNIEXPORT jint JNICALL
Java_app_localai_engine_NativeBridge_nContextSize(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g.ctx ? static_cast<jint>(llama_n_ctx(g.ctx)) : 0;
}

JNIEXPORT jboolean JNICALL
Java_app_localai_engine_NativeBridge_nTemplateIsExplicit(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g.template_explicit ? JNI_TRUE : JNI_FALSE;
}

// Applies the model's own chat template (Jinja, from GGUF metadata) and
// returns the rendered prompt as UTF-8, or null on failure. Used both for
// token counting (context budget) and for generation.
JNIEXPORT jbyteArray JNICALL
Java_app_localai_engine_NativeBridge_nRenderPrompt(JNIEnv *env, jobject,
                                                   jobjectArray jroles, jobjectArray jcontents) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g.model || !g.templates) return nullptr;
    try {
        common_chat_templates_inputs in;
        const jsize n = env->GetArrayLength(jroles);
        for (jsize i = 0; i < n; i++) {
            auto jr = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
            auto jc = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
            const char *r = env->GetStringUTFChars(jr, nullptr);
            common_chat_msg m;
            m.role = r;
            m.content = to_std(env, jc);
            env->ReleaseStringUTFChars(jr, r);
            env->DeleteLocalRef(jr);
            env->DeleteLocalRef(jc);
            in.messages.push_back(std::move(m));
        }
        in.add_generation_prompt = true;
        in.use_jinja = true;
        in.enable_thinking = true;
        const common_chat_params p = common_chat_templates_apply(g.templates.get(), in);
        return to_java(env, p.prompt);
    } catch (const std::exception &e) {
        LOGe("chat template failed: %s", e.what());
        return nullptr;
    }
}

// Counts tokens of a UTF-8 text with the loaded model's tokenizer; -1 on error.
JNIEXPORT jint JNICALL
Java_app_localai_engine_NativeBridge_nCountTokens(JNIEnv *env, jobject, jbyteArray jtext) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g.model) return -1;
    const std::string text = to_std(env, jtext);
    const llama_vocab *vocab = llama_model_get_vocab(g.model);
    return static_cast<jint>(common_tokenize(vocab, text, false, true).size());
}

// Builds the sampler chain. temp <= 0 means greedy decoding.
JNIEXPORT jint JNICALL
Java_app_localai_engine_NativeBridge_nSetSampling(JNIEnv *, jobject,
        jfloat temp, jint top_k, jfloat top_p, jfloat min_p,
        jfloat repeat_penalty, jint penalty_last_n, jlong seed) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g.model) return ERR_NO_MODEL;
    free_sampler();

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeat_penalty != 1.0f && penalty_last_n != 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(penalty_last_n, repeat_penalty, 0.0f, 0.0f));
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (top_k > 0)     llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
        if (top_p < 1.0f)  llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0f)  llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
        const uint32_t s = seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
        llama_sampler_chain_add(chain, llama_sampler_init_dist(s));
    }
    g.sampler = chain;
    return 0;
}

// Tokenizes the already-rendered prompt, reuses the KV-cache prefix shared with
// the previous prompt, decodes the remainder and arms generation.
JNIEXPORT jint JNICALL
Java_app_localai_engine_NativeBridge_nStartCompletion(JNIEnv *env, jobject, jbyteArray jprompt, jint max_new) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g.model) return ERR_NO_MODEL;
    if (!g.ctx || !g.sampler) return ERR_NO_CONTEXT;

    g_cancel = false;
    g.stop_reason = STOP_NONE;
    g.n_generated = 0;
    g.max_new = max_new;
    g.pending_bytes.clear();
    g.t_prompt_us = g.t_gen_us = 0;
    llama_sampler_reset(g.sampler);

    const std::string prompt = to_std(env, jprompt);
    const llama_vocab *vocab = llama_model_get_vocab(g.model);
    std::vector<llama_token> tokens = common_tokenize(vocab, prompt, false, true);
    if (llama_vocab_get_add_bos(vocab)) {
        const llama_token bos = llama_vocab_bos(vocab);
        if (tokens.empty() || tokens[0] != bos) tokens.insert(tokens.begin(), bos);
    }
    const int n_ctx = static_cast<int>(llama_n_ctx(g.ctx));
    if (tokens.empty() || static_cast<int>(tokens.size()) + 1 > n_ctx) return ERR_PROMPT_LONG;

    // Longest common prefix with what is already in the KV cache. At least the
    // last token must be decoded to obtain fresh logits.
    size_t keep = 0;
    const size_t lim = std::min(g.cached_tokens.size(), tokens.size() - 1);
    while (keep < lim && g.cached_tokens[keep] == tokens[keep]) keep++;
    llama_memory_t mem = llama_get_memory(g.ctx);
    if (keep == 0 || !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    g.cached_tokens.assign(tokens.begin(), tokens.begin() + keep);
    g.n_prompt_total = static_cast<int>(tokens.size());
    g.n_prompt_reused = static_cast<int>(keep);
    g.n_prompt_eval = static_cast<int>(tokens.size() - keep);

    const int64_t t0 = now_us();
    size_t pos = keep;
    while (pos < tokens.size()) {
        if (g_cancel) { g.cached_tokens.clear(); llama_memory_clear(mem, true); g.stop_reason = STOP_CANCELLED; return ERR_CANCELLED; }
        const size_t chunk = std::min<size_t>(g.n_batch, tokens.size() - pos);
        common_batch_clear(g.batch);
        for (size_t i = 0; i < chunk; i++) {
            const bool last = (pos + i == tokens.size() - 1);
            common_batch_add(g.batch, tokens[pos + i], static_cast<llama_pos>(pos + i), {0}, last);
        }
        const int rc = llama_decode(g.ctx, g.batch);
        if (rc != 0) {
            // Cache content is undefined after a failed/aborted decode.
            g.cached_tokens.clear();
            llama_memory_clear(mem, true);
            if (g_cancel) { g.stop_reason = STOP_CANCELLED; return ERR_CANCELLED; }
            LOGe("llama_decode failed during prompt processing: %d", rc);
            g.stop_reason = STOP_ERROR;
            return ERR_DECODE;
        }
        g.cached_tokens.insert(g.cached_tokens.end(), tokens.begin() + pos, tokens.begin() + pos + chunk);
        pos += chunk;
    }
    g.t_prompt_us = now_us() - t0;
    g.n_past = static_cast<int>(tokens.size());
    return 0;
}

// Returns the next chunk of UTF-8 bytes (possibly empty while a multi-byte
// character is incomplete), or null when generation has ended; then call
// nStopReason().
JNIEXPORT jbyteArray JNICALL
Java_app_localai_engine_NativeBridge_nNextToken(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g.ctx || !g.sampler) { g.stop_reason = STOP_ERROR; return nullptr; }
    if (g.stop_reason != STOP_NONE) return nullptr;

    if (g_cancel)                         { g.stop_reason = STOP_CANCELLED; return nullptr; }
    if (g.n_generated >= g.max_new)       { g.stop_reason = STOP_MAX;       return nullptr; }
    if (g.n_past >= static_cast<int>(llama_n_ctx(g.ctx))) { g.stop_reason = STOP_CTX_FULL; return nullptr; }

    const int64_t t0 = now_us();
    const llama_token tok = llama_sampler_sample(g.sampler, g.ctx, -1);  // also accepts the token
    const llama_vocab *vocab = llama_model_get_vocab(g.model);

    if (llama_vocab_is_eog(vocab, tok)) {
        g.stop_reason = STOP_EOG;
        g.t_gen_us += now_us() - t0;
        return nullptr;
    }

    common_batch_clear(g.batch);
    common_batch_add(g.batch, tok, g.n_past, {0}, true);
    const int rc = llama_decode(g.ctx, g.batch);
    g.t_gen_us += now_us() - t0;
    if (rc != 0) {
        g.cached_tokens.clear();
        llama_memory_clear(llama_get_memory(g.ctx), true);
        g.stop_reason = g_cancel ? STOP_CANCELLED : STOP_ERROR;
        if (g.stop_reason == STOP_ERROR) LOGe("llama_decode failed during generation: %d", rc);
        return nullptr;
    }
    g.cached_tokens.push_back(tok);
    g.n_past++;
    g.n_generated++;

    g.pending_bytes += common_token_to_piece(vocab, tok, false);
    const size_t ok = complete_utf8_prefix(g.pending_bytes);
    const std::string out = g.pending_bytes.substr(0, ok);
    g.pending_bytes.erase(0, ok);
    return to_java(env, out);
}

JNIEXPORT jint JNICALL
Java_app_localai_engine_NativeBridge_nStopReason(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g.stop_reason;
}

// Safe from any thread; does not take the session mutex.
JNIEXPORT void JNICALL
Java_app_localai_engine_NativeBridge_nCancel(JNIEnv *, jobject) {
    g_cancel.store(true, std::memory_order_relaxed);
}

// [promptTokensTotal, promptTokensReused, promptTokensEvaluated, promptMs, generatedTokens, generatedMs]
JNIEXPORT jlongArray JNICALL
Java_app_localai_engine_NativeBridge_nStats(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const jlong v[6] = {g.n_prompt_total, g.n_prompt_reused, g.n_prompt_eval,
                        g.t_prompt_us / 1000, g.n_generated, g.t_gen_us / 1000};
    jlongArray arr = env->NewLongArray(6);
    env->SetLongArrayRegion(arr, 0, 6, v);
    return arr;
}

JNIEXPORT void JNICALL
Java_app_localai_engine_NativeBridge_nResetContext(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g.ctx) llama_memory_clear(llama_get_memory(g.ctx), true);
    g.cached_tokens.clear();
    g.n_past = 0;
    g.stop_reason = STOP_NONE;
}

JNIEXPORT void JNICALL
Java_app_localai_engine_NativeBridge_nFreeContext(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_context();
}

JNIEXPORT void JNICALL
Java_app_localai_engine_NativeBridge_nUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model();
}

} // extern "C"
