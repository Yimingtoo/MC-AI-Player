package com.yiming.mcp_host.llm;

public class TaskCancelledException extends RuntimeException {
    public TaskCancelledException(String message) {
        super(message);
    }
}
