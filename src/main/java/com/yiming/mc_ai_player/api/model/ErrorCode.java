package com.yiming.mc_ai_player.api.model;

import com.google.gson.annotations.SerializedName;

public enum ErrorCode {
    @SerializedName("invalid_parameters")
    INVALID_PARAMETERS("invalid_parameters"),
    @SerializedName("not_in_world")
    NOT_IN_WORLD("not_in_world"),
    @SerializedName("player_not_found")
    PLAYER_NOT_FOUND("player_not_found"),
    @SerializedName("out_of_range")
    OUT_OF_RANGE("out_of_range"),
    @SerializedName("block_blacklisted")
    BLOCK_BLACKLISTED("block_blacklisted"),
    @SerializedName("command_disallowed")
    COMMAND_DISALLOWED("command_disallowed"),
    @SerializedName("dimension_disallowed")
    DIMENSION_DISALLOWED("dimension_disallowed"),
    @SerializedName("rate_limited")
    RATE_LIMITED("rate_limited"),
    @SerializedName("internal_error")
    INTERNAL_ERROR("internal_error"),
    @SerializedName("operation_disabled")
    OPERATION_DISABLED("operation_disabled"),
    @SerializedName("volume_exceeded")
    VOLUME_EXCEEDED("volume_exceeded"),
    @SerializedName("block_not_found")
    BLOCK_NOT_FOUND("block_not_found");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
