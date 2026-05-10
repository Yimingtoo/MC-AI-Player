package com.yiming.mc_ai_player.api.model;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

public class ActionResponse {
    private static final Gson GSON = new Gson();

    public boolean success;
    public JsonElement data;
    public ErrorDetail error;

    public static ActionResponse ok(Object obj) {
        ActionResponse r = new ActionResponse();
        r.success = true;
        r.data = GSON.toJsonTree(obj);
        return r;
    }

    public static ActionResponse error(ErrorCode code, String message) {
        ActionResponse r = new ActionResponse();
        r.success = false;
        r.error = new ErrorDetail(code.getCode(), message);
        return r;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static class ErrorDetail {
        public String code;
        public String message;

        public ErrorDetail(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
