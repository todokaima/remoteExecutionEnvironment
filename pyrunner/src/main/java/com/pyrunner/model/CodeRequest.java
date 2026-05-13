package com.pyrunner.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CodeRequest {

    @NotBlank(message = "Code cannot be empty")
    @Size(max = 50_000, message = "Code must be under 50,000 characters")
    private String code;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
