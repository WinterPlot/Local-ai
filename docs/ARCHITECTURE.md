# Architecture
- `engine/`: `InferenceEngine` interface, `LlamaEngine` (llama.cpp via JNI, one dedicated native thread, atomic cancel flag + ggml abort callback), `FakeInferenceEngine` for tests, GGUF header parser, prompt budget, memory estimator, stop-sequence filter.
- `app/`: Room (conversations, messages, models), DataStore settings, `ModelRepository` (SAF import), ViewModels, Compose screens.
- Prompts are rendered with the model's own Jinja chat template; oldest turns are dropped to fit the context and the UI says so.
