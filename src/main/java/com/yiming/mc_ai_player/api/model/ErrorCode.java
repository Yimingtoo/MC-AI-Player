package com.yiming.mc_ai_player.api.model;

import com.google.gson.annotations.SerializedName;

public enum ErrorCode {
    @SerializedName("invalid_parameters")
    INVALID_PARAMETERS,
    @SerializedName("not_in_world")
    NOT_IN_WORLD,
    @SerializedName("player_not_found")
    PLAYER_NOT_FOUND,
    @SerializedName("out_of_range")
    OUT_OF_RANGE,
    @SerializedName("block_blacklisted")
    BLOCK_BLACKLISTED,
    @SerializedName("command_disallowed")
    COMMAND_DISALLOWED,
    @SerializedName("dimension_disallowed")
    DIMENSION_DISALLOWED,
    @SerializedName("rate_limited")
    RATE_LIMITED,
    @SerializedName("internal_error")
    INTERNAL_ERROR,
    @SerializedName("operation_disabled")
    OPERATION_DISABLED,
    @SerializedName("volume_exceeded")
    VOLUME_EXCEEDED,
    @SerializedName("block_not_found")
    BLOCK_NOT_FOUND
}
