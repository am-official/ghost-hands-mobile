// ghhands.c -- Ghost Hands: tiny JNI shim over the llama.cpp C API
// (pinned tree b11382, MIT). Pure C; the llama.cpp API is C, so no C++
// is needed here.
//
// Java side: com.ghosthands.app.NativeBridge
//   loadModel(path, nThreads)             -> handle (jlong) or 0
//       (v3.6: the BRAIN thread count, clamped to [1, 8] here and
//       baked into the context at creation; applies at load only)
//   generate(handle, userPrompt, maxTok, useGrammar, usePrefixCache)
//                                         -> String[8]:
//        [0] generated text (or "ERROR: ...")
//        [1] prompt token count
//        [2] prompt eval elapsed ms (v1.5: suffix-only when the
//            prefix cache hits; the reused prefix is not re-decoded)
//        [3] generated token count
//        [4] generation elapsed ms
//        [5] prompt tokens REUSED via the prefix cache this call
//        [6] prompt tokens actually EVALUATED this call
//        [7] "1" when this call's sampling was grammar-constrained
//            (v2.4), else "0"
//   freeModel(handle)
//
// v1.5 PREFIX CACHE: the handle remembers the previous call's prompt
// tokens — their KV (plus the generation that followed) is still in
// the context. When the new prompt shares a prefix of >= 64 tokens
// with the cached one, the sequence's KV is trimmed back to exactly
// that prefix (llama_memory_seq_rm) and only the suffix is decoded.
// The final prompt token is always re-decoded (even for an identical
// prompt) so sampling has fresh logits. Any decode failure clears
// the memory and invalidates the cache before the error returns.
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
    /* Prefix cache (v1.5): the previous generate() call's prompt
     * tokens. Their KV occupies positions [0, cache_len) of seq 0,
     * followed by whatever that call generated. NULL/0 = no cache. */
    llama_token * cache;
    int32_t cache_len;
} GhHandle;

/* v2.4 GRAMMAR-LOCKED OUTPUT: a GBNF grammar (the pinned llama.h's
 * llama_sampler_init_grammar) constraining the planner's generation
 * to exactly ONE JSON object of the action shapes the Java side
 * parses. The field set is DERIVED from GhAction / PlannerParse,
 * with "action" first, as the prompt contract and every worked
 * example write it. It grew with the action vocabulary: note
 * (v2.x), choose/toggle (v3.9), ask (v3.12), the tab actions
 * (v3.15), read_file (v3.16). The parser's non-blank fields ride
 * nstring (v3.11's parity discipline: the grammar never emits a
 * shape PlannerParse would reject); rule names use hyphens, not
 * underscores -- the pinned GBNF parser's word characters are
 * [a-zA-Z0-9-] (the v3.15 lesson). Strings accept arbitrary text
 * (a done summary is prose) with the standard JSON escapes;
 * numbers here are the parser's integer fields (el is 1-based,
 * so elnum excludes 0). Range checks the grammar cannot express
 * (wait's clamp, el's existence) stay with PlannerParse, which
 * remains in force unchanged as the second line of defense. The
 * grammar constrains SYNTAX only: it can make malformed JSON
 * impossible, never a wrong action sensible.
 * Written in the pinned tree's own grammars/*.gbnf conventions.
 * v2.6.1: this grammar is only ever handed to llama.cpp when the
 * caller's kill-switch flag says ON; see the sampler chain in
 * generate(). */
static const char * GH_ACTION_GRAMMAR =
    "root ::= ws obj ws\n"
    "obj ::= tap | type | goto | scroll | back | wait | search | read | done | stuck | choose | toggle | note | ask | open-tab | switch-tab | close-tab | read-file\n"
    "choose ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"choose\\\"\" ws \",\" ws \"\\\"el\\\"\" ws \":\" ws elnum ws \",\" ws \"\\\"text\\\"\" ws \":\" ws nstring ws \"}\"\n"
    "toggle ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"toggle\\\"\" ws \",\" ws \"\\\"el\\\"\" ws \":\" ws elnum (ws \",\" ws \"\\\"text\\\"\" ws \":\" ws string)? ws \"}\"\n"
    "tap ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"tap\\\"\" ws \",\" ws \"\\\"el\\\"\" ws \":\" ws elnum ws \"}\"\n"
    "type ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"type\\\"\" ws \",\" ws \"\\\"el\\\"\" ws \":\" ws elnum ws \",\" ws \"\\\"text\\\"\" ws \":\" ws string ws \"}\"\n"
    "goto ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"goto\\\"\" ws \",\" ws \"\\\"url\\\"\" ws \":\" ws nstring ws \"}\"\n"
    "scroll ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"scroll\\\"\" (ws \",\" ws \"\\\"dir\\\"\" ws \":\" ws dir)? ws \"}\"\n"
    "dir ::= \"\\\"down\\\"\" | \"\\\"up\\\"\"\n"
    "back ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"back\\\"\" ws \"}\"\n"
    "wait ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"wait\\\"\" (ws \",\" ws \"\\\"ms\\\"\" ws \":\" ws posint)? ws \"}\"\n"
    "search ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"search\\\"\" ws \",\" ws \"\\\"query\\\"\" ws \":\" ws nstring ws \"}\"\n"
    "read ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"read\\\"\" ws \"}\"\n"
    "done ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"done\\\"\" (ws \",\" ws \"\\\"summary\\\"\" ws \":\" ws string)? ws \"}\"\n"
    "stuck ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"stuck\\\"\" (ws \",\" ws \"\\\"reason\\\"\" ws \":\" ws string)? ws \"}\"\n"
    "note ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"note\\\"\" ws \",\" ws \"\\\"fact\\\"\" ws \":\" ws nstring ws \"}\"\n"
    "ask ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"ask\\\"\" ws \",\" ws \"\\\"question\\\"\" ws \":\" ws nstring ws \"}\"\n"
    "open-tab ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"open_tab\\\"\" (ws \",\" ws \"\\\"url\\\"\" ws \":\" ws nstring)? ws \"}\"\n"
    "switch-tab ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"switch_tab\\\"\" ws \",\" ws \"\\\"tab\\\"\" ws \":\" ws elnum ws \"}\"\n"
    "close-tab ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"close_tab\\\"\" ws \",\" ws \"\\\"tab\\\"\" ws \":\" ws elnum ws \"}\"\n"
    "read-file ::= \"{\" ws \"\\\"action\\\"\" ws \":\" ws \"\\\"read_file\\\"\" ws \",\" ws \"\\\"name\\\"\" ws \":\" ws nstring ws \"}\"\n"
    "string ::= \"\\\"\" schar* \"\\\"\"\n"
    "nstring ::= \"\\\"\" schar schar* \"\\\"\"\n"
    "schar ::= [^\"\\\\\\x00-\\x1F] | \"\\\\\" esc\n"
    "esc ::= [\"\\\\/bfnrt] | \"u\" hex hex hex hex\n"
    "hex ::= [0-9a-fA-F]\n"
    "elnum ::= [1-9] [0-9]*\n"
    "posint ::= [0-9]+\n"
    "ws ::= (\" \" | \"\\t\" | \"\\n\")*\n";

static int g_backend_ready = 0;

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

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double) ts.tv_sec * 1000.0 + (double) ts.tv_nsec / 1.0e6;
}

JNIEXPORT jlong JNICALL
Java_com_ghosthands_app_NativeBridge_loadModel(JNIEnv * env, jclass clazz,
                                                 jstring jpath,
                                                 jint n_threads) {
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
    /* v3.6: the BRAIN setting's thread count, baked in at load;
     * clamped to the range the context accepts. */
    if (n_threads < 1) n_threads = 1;
    if (n_threads > 8) n_threads = 8;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
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
Java_com_ghosthands_app_NativeBridge_freeModel(JNIEnv * env, jclass clazz,
                                                 jlong jhandle) {
    (void) env; (void) clazz;
    if (jhandle == 0) return;
    GhHandle * h = (GhHandle *) (intptr_t) jhandle;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    free(h->cache);
    free(h);
}

JNIEXPORT jobjectArray JNICALL
Java_com_ghosthands_app_NativeBridge_generate(JNIEnv * env, jclass clazz,
                                                jlong jhandle,
                                                jstring jprompt,
                                                jint max_tokens,
                                                jboolean use_grammar,
                                                jboolean use_prefix_cache) {
    (void) clazz;
    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, 8, str_cls, NULL);
    if (out == NULL) return NULL;
    set_result(env, out, 0, "");
    set_result_long(env, out, 1, 0);
    set_result_long(env, out, 2, 0);
    set_result_long(env, out, 3, 0);
    set_result_long(env, out, 4, 0);
    set_result_long(env, out, 5, 0);
    set_result_long(env, out, 6, 0);
    set_result_long(env, out, 7, 0);

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

    /* --- prefix cache: longest common prefix with the cached prompt.
     * The cache is consulted only when it could hold a real prefix;
     * the final prompt token is always (re)decoded so the sampler
     * sees fresh logits, so reuse is capped at n_prompt - 1. --- */
    int reuse = 0;
    /* v3.6: the prefix-cache switch OFF drops the cached prompt
     * and skips both the consult below and the refresh after
     * prompt eval, so the call evaluates its prompt in full --
     * the pre-v1.5 behaviour, kept for A/B diagnosis. */
    if (use_prefix_cache != JNI_TRUE) {
        free(h->cache);
        h->cache = NULL;
        h->cache_len = 0;
    }
    if (h->cache != NULL && h->cache_len > 0) {
        int32_t limit = h->cache_len < n_prompt ? h->cache_len : n_prompt;
        int32_t lcp = 0;
        while (lcp < limit && h->cache[lcp] == tokens[lcp]) lcp++;
        if (lcp >= 64 && n_prompt >= 2) {
            reuse = (int) lcp;
            if (reuse > n_prompt - 1) reuse = n_prompt - 1;
        }
    }

    llama_memory_t mem = llama_get_memory(h->ctx);
    if (reuse > 0) {
        /* Trim seq 0's KV (old prompt tail + last call's generated
         * tokens) back to exactly the shared prefix. p1 < 0 means
         * [p0, inf) per the pinned header. If a partial removal is
         * refused, fall back to a full clear and a full decode. */
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) reuse, -1)) {
            llama_memory_clear(mem, true);
            reuse = 0;
        }
    } else {
        /* No usable cache: fresh KV, exactly as before v1.5. */
        llama_memory_clear(mem, true);
    }

    /* Sampler chain. v2.6.1 KILL-SWITCH: the Java-side "Output
     * grammar lock" setting arrives here as use_grammar. When it is
     * OFF (the default), the grammar sampler is never created and
     * the chain is byte-for-byte the pre-v2.4 one -- the greedy
     * sampler alone -- so PlannerParse is again the only format
     * defense, exactly as in v1.0-v2.3; result [7] keeps its
     * initialised 0. When it is ON, v2.4's behaviour, unchanged:
     * the GBNF grammar sampler first (it masks every token that
     * cannot continue a valid action object), then the greedy
     * sampler that picks among what survives -- deterministic
     * output, now with the syntax guaranteed. If the grammar fails
     * to initialise (llama_sampler_init_grammar returns NULL on a
     * parse failure), the chain degrades to greedy-only and result
     * [7] reports 0, so the Java side can see on the trail that
     * this call was NOT constrained. The flag is set here, before
     * any generation, so every return path below reports it. */
    struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    struct llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (use_grammar == JNI_TRUE) {
        struct llama_sampler * gsmpl = llama_sampler_init_grammar(
                h->vocab, GH_ACTION_GRAMMAR, "root");
        if (gsmpl != NULL) {
            llama_sampler_chain_add(smpl, gsmpl);
            set_result_long(env, out, 7, 1);
        }
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    struct llama_batch batch = llama_batch_init(512, 0, 1);

    /* --- prompt eval: only the suffix after the reused prefix
     * (chunks of up to 512, logits only on the last prompt token) --- */
    const char * error = NULL;
    double t0 = now_ms();
    for (int s = reuse; s < n_prompt && error == NULL; s += 512) {
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

    if (error == NULL && use_prefix_cache == JNI_TRUE) {
        /* The prompt's KV is now resident: remember its tokens as
         * the cache for the next call. A malloc failure just loses
         * the cache; it never fails the call. */
        llama_token * ncache = (llama_token *) malloc(
                (size_t) n_prompt * sizeof(llama_token));
        if (ncache != NULL) {
            memcpy(ncache, tokens, (size_t) n_prompt * sizeof(llama_token));
            free(h->cache);
            h->cache = ncache;
            h->cache_len = n_prompt;
        } else {
            free(h->cache);
            h->cache = NULL;
            h->cache_len = 0;
        }
    }
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
            /* v3.11: NO explicit accept here. At the pinned
             * llama.cpp (b11382), llama_sampler_sample already
             * accepts the token it returns into the chain; a
             * second accept fed every token to the grammar
             * sampler twice and emptied its stacks, killing the
             * process on the first grammar-constrained token.
             * GovernorTest pins this file free of the call. */
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
        /* A decode failed somewhere: the KV state is no longer
         * trustworthy. Clear it and drop the cache so the next call
         * starts clean, then return the same error as before v1.5. */
        llama_memory_clear(llama_get_memory(h->ctx), true);
        free(h->cache);
        h->cache = NULL;
        h->cache_len = 0;
        free(text);
        set_result(env, out, 0, error);
        return out;
    }

    set_result(env, out, 0, text != NULL ? text : "");
    set_result_long(env, out, 1, (long) n_prompt);
    set_result_long(env, out, 2, (long) (t1 - t0));
    set_result_long(env, out, 3, (long) n_gen);
    set_result_long(env, out, 4, (long) (t2 - t1));
    set_result_long(env, out, 5, (long) reuse);
    set_result_long(env, out, 6, (long) (n_prompt - reuse));
    free(text);
    return out;
}
