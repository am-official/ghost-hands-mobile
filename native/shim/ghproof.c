// ghproof.c -- Ghost Hands Proof: tiny JNI shim over the llama.cpp C API
// (pinned tree b11382, MIT). Pure C; the llama.cpp API is C, so no C++
// is needed here.
//
// Java side: com.ghosthands.proof.NativeBridge
//   loadModel(path)                       -> handle (jlong) or 0
//   generate(handle, userPrompt, maxTok)  -> String[5]:
//        [0] generated text (or "ERROR: ...")
//        [1] prompt token count
//        [2] prompt eval elapsed ms
//        [3] generated token count
//        [4] generation elapsed ms
//   freeModel(handle)
//
// generate() wraps the user prompt with the model's own chat template
// (llama_model_chat_template + llama_chat_apply_template; Qwen2.5 uses
// ChatML) and samples greedily so the benchmark is deterministic.

#include <jni.h>
#include "llama.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    struct llama_model * model;
    struct llama_context * ctx;
    const struct llama_vocab * vocab;
} GhHandle;

static int g_backend_ready = 0;

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double) ts.tv_sec * 1000.0 + (double) ts.tv_nsec / 1.0e6;
}

JNIEXPORT jlong JNICALL
Java_com_ghosthands_proof_NativeBridge_loadModel(JNIEnv * env, jclass clazz,
                                                 jstring jpath) {
    (void) clazz;
    const char * path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return 0;

    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = 1;
    }

    struct llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; /* CPU only */
    struct llama_model * model = llama_model_load_from_file(path, mp);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (model == NULL) return 0;

    struct llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = 4;
    cp.n_threads_batch = 4;
    struct llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == NULL) {
        llama_model_free(model);
        return 0;
    }

    GhHandle * h = (GhHandle *) calloc(1, sizeof(GhHandle));
    if (h == NULL) {
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    return (jlong) (intptr_t) h;
}

JNIEXPORT void JNICALL
Java_com_ghosthands_proof_NativeBridge_freeModel(JNIEnv * env, jclass clazz,
                                                 jlong jhandle) {
    (void) env; (void) clazz;
    if (jhandle == 0) return;
    GhHandle * h = (GhHandle *) (intptr_t) jhandle;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    free(h);
}

static void set_result(JNIEnv * env, jobjectArray out, int idx,
                       const char * s) {
    jstring js = (*env)->NewStringUTF(env, s);
    if (js != NULL) {
        (*env)->SetObjectArrayElement(env, out, idx, js);
        (*env)->DeleteLocalRef(env, js);
    }
}

static void set_result_long(JNIEnv * env, jobjectArray out, int idx,
                            long v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%ld", v);
    set_result(env, out, idx, buf);
}

JNIEXPORT jobjectArray JNICALL
Java_com_ghosthands_proof_NativeBridge_generate(JNIEnv * env, jclass clazz,
                                                jlong jhandle,
                                                jstring jprompt,
                                                jint max_tokens) {
    (void) clazz;
    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, 5, str_cls, NULL);
    if (out == NULL) return NULL;
    set_result(env, out, 0, "");
    set_result_long(env, out, 1, 0);
    set_result_long(env, out, 2, 0);
    set_result_long(env, out, 3, 0);
    set_result_long(env, out, 4, 0);

    GhHandle * h = (GhHandle *) (intptr_t) jhandle;
    if (h == NULL || h->ctx == NULL) {
        set_result(env, out, 0, "ERROR: no model loaded");
        return out;
    }

    const char * pchars = (*env)->GetStringUTFChars(env, jprompt, NULL);
    if (pchars == NULL) {
        set_result(env, out, 0, "ERROR: no prompt");
        return out;
    }

    /* --- format the prompt with the model's chat template --- */
    const char * tmpl = llama_model_chat_template(h->model, NULL);
    struct llama_chat_message msg;
    msg.role = "user";
    msg.content = pchars;
    int32_t need = llama_chat_apply_template(tmpl, &msg, 1, true, NULL, 0);
    char * formatted = NULL;
    if (need > 0) {
        formatted = (char *) malloc((size_t) need + 1);
        if (formatted != NULL) {
            int32_t wrote = llama_chat_apply_template(tmpl, &msg, 1, true,
                                                      formatted, need + 1);
            if (wrote < 0) {
                free(formatted);
                formatted = NULL;
            }
        }
    }
    if (formatted == NULL) {
        /* Fallback: Qwen2.5 ChatML by hand. */
        const char * pre = "<|im_start|>user\n";
        const char * post = "<|im_end|>\n<|im_start|>assistant\n";
        size_t len = strlen(pre) + strlen(pchars) + strlen(post);
        formatted = (char *) malloc(len + 1);
        if (formatted == NULL) {
            (*env)->ReleaseStringUTFChars(env, jprompt, pchars);
            set_result(env, out, 0, "ERROR: out of memory");
            return out;
        }
        snprintf(formatted, len + 1, "%s%s%s", pre, pchars, post);
    }
    (*env)->ReleaseStringUTFChars(env, jprompt, pchars);

    /* --- tokenize --- */
    const struct llama_vocab * vocab = h->vocab;
    int32_t n_prompt = -llama_tokenize(vocab, formatted, (int32_t) strlen(formatted),
                                       NULL, 0, true, true);
    if (n_prompt <= 0) {
        free(formatted);
        set_result(env, out, 0, "ERROR: tokenize failed");
        return out;
    }
    llama_token * tokens = (llama_token *) malloc((size_t) n_prompt * sizeof(llama_token));
    if (tokens == NULL ||
        llama_tokenize(vocab, formatted, (int32_t) strlen(formatted),
                       tokens, n_prompt, true, true) < 0) {
        free(tokens);
        free(formatted);
        set_result(env, out, 0, "ERROR: tokenize failed (2nd call)");
        return out;
    }
    free(formatted);

    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if (n_prompt + 16 >= n_ctx) {
        free(tokens);
        set_result(env, out, 0, "ERROR: prompt too long for context");
        return out;
    }

    /* Fresh KV for every generation. */
    llama_memory_clear(llama_get_memory(h->ctx), true);

    /* Greedy sampler: deterministic benchmark output. */
    struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    struct llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    struct llama_batch batch = llama_batch_init(512, 0, 1);

    /* --- prompt eval (chunks of up to 512, logits only on last token) --- */
    const char * error = NULL;
    double t0 = now_ms();
    for (int s = 0; s < n_prompt && error == NULL; s += 512) {
        int m = (n_prompt - s < 512) ? (n_prompt - s) : 512;
        batch.n_tokens = m;
        for (int i = 0; i < m; i++) {
            batch.token[i] = tokens[s + i];
            batch.pos[i] = s + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (s + i == n_prompt - 1) ? 1 : 0;
        }
        if (llama_decode(h->ctx, batch) != 0) {
            error = "ERROR: llama_decode failed on prompt";
        }
    }
    double t1 = now_ms();
    free(tokens);

    /* --- generation --- */
    char * text = NULL;
    size_t text_len = 0, text_cap = 0;
    int n_gen = 0;
    int cur_pos = n_prompt;
    double t2 = t1;
    if (error == NULL) {
        t2 = now_ms();
        char piece_buf[256];
        for (int g = 0; g < max_tokens; g++) {
            llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
            llama_sampler_accept(smpl, id);
            if (llama_vocab_is_eog(vocab, id)) break;

            int n = llama_token_to_piece(vocab, id, piece_buf,
                                         (int32_t) sizeof(piece_buf), 0, true);
            if (n < 0) { error = "ERROR: token_to_piece failed"; break; }
            if (text_len + (size_t) n + 1 > text_cap) {
                size_t ncap = (text_cap == 0) ? 512 : text_cap * 2;
                while (ncap < text_len + (size_t) n + 1) ncap *= 2;
                char * nt = (char *) realloc(text, ncap);
                if (nt == NULL) { error = "ERROR: out of memory"; break; }
                text = nt;
                text_cap = ncap;
            }
            memcpy(text + text_len, piece_buf, (size_t) n);
            text_len += (size_t) n;
            text[text_len] = '\0';
            n_gen++;

            batch.n_tokens = 1;
            batch.token[0] = id;
            batch.pos[0] = cur_pos++;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = 1;
            if (llama_decode(h->ctx, batch) != 0) {
                error = "ERROR: llama_decode failed during generation";
                break;
            }
        }
        t2 = now_ms();
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);

    if (error != NULL) {
        free(text);
        set_result(env, out, 0, error);
        return out;
    }

    set_result(env, out, 0, text != NULL ? text : "");
    set_result_long(env, out, 1, (long) n_prompt);
    set_result_long(env, out, 2, (long) (t1 - t0));
    set_result_long(env, out, 3, (long) n_gen);
    set_result_long(env, out, 4, (long) (t2 - t1));
    free(text);
    return out;
}
