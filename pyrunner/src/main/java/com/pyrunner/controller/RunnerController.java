package com.pyrunner.controller;

import com.pyrunner.model.CodeRequest;
import com.pyrunner.model.ExecutionResult;
import com.pyrunner.service.KubernetesExecutionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class RunnerController {

    private static final Logger log = LoggerFactory.getLogger(RunnerController.class);
    private final KubernetesExecutionService executionService;

    public RunnerController(KubernetesExecutionService executionService) {
        this.executionService = executionService;
    }

    /** Serves the main editor page */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("codeRequest", new CodeRequest());
        return "index";
    }

    /**
     * REST endpoint called by the frontend via fetch().
     * Returns JSON so the UI can update without a full page reload.
     */
    @PostMapping("/run")
    @ResponseBody
    public ResponseEntity<?> run(@Valid @RequestBody CodeRequest request, BindingResult binding) {
        if (binding.hasErrors()) {
            String msg = binding.getAllErrors().get(0).getDefaultMessage();
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }

        try {
            ExecutionResult result = executionService.execute(request.getCode());
            return ResponseEntity.ok(Map.of(
                    "stdout",     result.stdout(),
                    "stderr",     result.stderr(),
                    "exitCode",   result.exitCode(),
                    "durationMs", result.durationMs(),
                    "timedOut",   result.timedOut(),
                    "success",    result.isSuccess()
            ));
        } catch (Exception e) {
            log.error("Execution error", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Server error: " + e.getMessage()));
        }
    }
}
