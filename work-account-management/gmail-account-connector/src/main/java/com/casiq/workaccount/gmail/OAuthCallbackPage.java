package com.casiq.workaccount.gmail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@ApplicationScoped
class OAuthCallbackPage {
    @Inject ObjectMapper mapper;

    String success(Map<String, Object> payload) {
        return page(encoded(payload));
    }

    String error(String message) {
        return page(encoded(Map.of("error", message)));
    }

    private String encoded(Object payload) {
        try {
            return Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not render OAuth callback", e);
        }
    }

    private String page(String encodedPayload) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Google authorization</title>
                <style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f4f7f3;color:#18211e;font-family:system-ui,sans-serif}.card{width:min(420px,calc(100%% - 40px));background:white;border:1px solid #dce4df;border-radius:16px;padding:28px;text-align:center;box-shadow:0 16px 45px #1b382b0e}.mark{width:42px;height:42px;display:grid;place-items:center;margin:auto;border-radius:12px;background:#176b4d;color:white;font-weight:700}h1{font-size:20px;margin:18px 0 8px}p{color:#68736f;font-size:13px;line-height:1.5}pre{text-align:left;white-space:pre-wrap;word-break:break-all;background:#f3f6f4;padding:12px;border-radius:8px;font-size:10px}</style>
                </head><body><div class="card"><div class="mark">C</div><h1 id="title">Completing authorization…</h1><p id="message">Returning the result to the test console.</p><pre id="fallback" hidden></pre></div>
                <script>
                const bytes=Uint8Array.from(atob('%s'),c=>c.charCodeAt(0));
                const payload=JSON.parse(new TextDecoder().decode(bytes));
                const message={type:'casiq-google-oauth',...payload};
                if(window.opener&&!window.opener.closed){window.opener.postMessage(message,window.location.origin);window.close()}
                else{document.querySelector('#title').textContent=payload.error?'Authorization failed':'Authorization complete';document.querySelector('#message').textContent=payload.error||'No originating window was found. The response is shown below.';const fallback=document.querySelector('#fallback');if(payload.tokens||payload.workAccount){fallback.hidden=false;fallback.textContent=JSON.stringify(payload.tokens||payload.workAccount,null,2)}}
                </script></body></html>
                """.formatted(encodedPayload);
    }
}
