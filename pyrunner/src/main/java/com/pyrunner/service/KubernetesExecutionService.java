package com.pyrunner.service;

import com.pyrunner.model.ExecutionResult;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class KubernetesExecutionService {

    private static final Logger log = LoggerFactory.getLogger(KubernetesExecutionService.class);

    @Value("${runner.k8s.namespace:sandbox}")
    private String namespace;

    @Value("${runner.k8s.image:python:3.11-slim}")
    private String image;

    @Value("${runner.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${runner.memory-limit:64Mi}")
    private String memoryLimit;

    @Value("${runner.cpu-limit:500m}")
    private String cpuLimit;

    private BatchV1Api batchApi;
    private CoreV1Api coreApi;

    @PostConstruct
    public void init() throws IOException {
        ApiClient client = Config.defaultClient();

        // safer than infinite timeout
        client.setReadTimeout(timeoutSeconds * 1000 + 10000);

        Configuration.setDefaultApiClient(client);

        batchApi = new BatchV1Api();
        coreApi = new CoreV1Api();

        log.info("Kubernetes client initialised (namespace={})", namespace);
    }

    public ExecutionResult execute(String code) throws ApiException, InterruptedException {

        String id = "pyrun-" + UUID.randomUUID().toString().substring(0, 8);

        long start = System.currentTimeMillis();

        /*
         * CONFIG MAP
         */
        V1ConfigMap cm = new V1ConfigMapBuilder()
                .withNewMetadata()
                .withName(id)
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", "pyrunner",
                        "run-id", id
                ))
                .endMetadata()
                .withData(Map.of(
                        "script.py", code
                ))
                .build();

        coreApi.createNamespacedConfigMap(namespace, cm).execute();

        log.info("Created ConfigMap [{}]", id);

        /*
         * JOB
         */
        V1Job job = new V1JobBuilder()
                .withNewMetadata()
                .withName(id)
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", "pyrunner",
                        "run-id", id
                ))
                .endMetadata()

                .withNewSpec()
                .withBackoffLimit(0)
                .withTtlSecondsAfterFinished(60)

                .withNewTemplate()

                .withNewMetadata()
                .withLabels(Map.of(
                        "app", "pyrunner",
                        "run-id", id
                ))
                .endMetadata()

                .withNewSpec()
                .withRestartPolicy("Never")
                .withAutomountServiceAccountToken(false)

                .addNewContainer()
                .withName("runner")
                .withImage(image)
                .withCommand("python", "/sandbox/script.py")

                .withNewSecurityContext()
                .withRunAsNonRoot(true)
                .withRunAsUser(1000L)
                .withReadOnlyRootFilesystem(true)
                .withAllowPrivilegeEscalation(false)
                .endSecurityContext()

                .withNewResources()
                .withLimits(Map.of(
                        "memory", new Quantity(memoryLimit),
                        "cpu", new Quantity(cpuLimit)
                ))
                .endResources()

                .addNewVolumeMount()
                .withName("script")
                .withMountPath("/sandbox")
                .withReadOnly(true)
                .endVolumeMount()

                .addNewVolumeMount()
                .withName("tmp")
                .withMountPath("/tmp")
                .endVolumeMount()

                .endContainer()

                .addNewVolume()
                .withName("script")
                .withNewConfigMap()
                .withName(id)
                .endConfigMap()
                .endVolume()

                .addNewVolume()
                .withName("tmp")
                .withNewEmptyDir()
                .withSizeLimit(new Quantity("16Mi"))
                .endEmptyDir()
                .endVolume()

                .endSpec()

                .endTemplate()

                .endSpec()

                .build();

        batchApi.createNamespacedJob(namespace, job).execute();

        log.info("Created Job [{}]", id);

        /*
         * EXECUTION STATE
         */
        String podName = null;
        boolean timedOut = false;
        int exitCode = -1;

        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        /*
         * POLL JOB STATUS ONLY
         */
        while (System.currentTimeMillis() < deadline) {

            TimeUnit.MILLISECONDS.sleep(500);

            try {

                V1Job current = batchApi
                        .readNamespacedJob(id, namespace)
                        .execute();

                V1JobStatus status = current.getStatus();

                if (status != null) {

                    if (isTrue(status.getSucceeded())) {
                        exitCode = 0;
                        break;
                    }

                    if (isTrue(status.getFailed())) {
                        exitCode = 1;
                        break;
                    }
                }

            } catch (ApiException e) {

                log.warn("Error reading job [{}]: {}", id, e.getMessage());
            }
        }

        /*
         * TIMEOUT
         */
        if (exitCode == -1) {

            timedOut = true;

            log.warn("Job [{}] timed out after {}s", id, timeoutSeconds);

            try {
                batchApi.deleteNamespacedJob(id, namespace)
                        .propagationPolicy("Foreground")
                        .execute();

            } catch (Exception e) {

                log.warn("Failed to delete timed out job [{}]: {}", id, e.getMessage());
            }
        }

        /*
         * DISCOVER POD AFTER JOB COMPLETES
         */
        if (!timedOut) {

            try {

                V1PodList pods = coreApi.listNamespacedPod(namespace)
                        .labelSelector("job-name=" + id)
                        .limit(1)
                        .execute();

                if (!pods.getItems().isEmpty()) {

                    podName = pods.getItems()
                            .get(0)
                            .getMetadata()
                            .getName();

                    log.info("Found pod [{}] for job [{}]", podName, id);
                }

            } catch (Exception e) {

                log.warn("Could not resolve pod name for job [{}]: {}", id, e.getMessage());
            }
        }

        /*
         * FETCH LOGS
         */
        String stdout = "";

        String stderr = timedOut
                ? "Execution timed out after " + timeoutSeconds + " seconds."
                : "";

        if (podName != null) {

            for (int attempt = 0; attempt < 8; attempt++) {

                try {

                    String logs = coreApi.readNamespacedPodLog(
                                    podName,
                                    namespace
                            )
                            .container("runner")
                            .execute();

                    if (logs != null && !logs.isBlank()) {

                        stdout = logs;
                        break;
                    }

                    log.debug("Empty logs on attempt {}/8 for pod [{}]",
                            attempt + 1,
                            podName);

                } catch (ApiException e) {

                    log.debug("Log fetch attempt {}/8 failed for pod [{}]: {}",
                            attempt + 1,
                            podName,
                            e.getMessage());
                }

                TimeUnit.MILLISECONDS.sleep(500);
            }

        } else {

            log.warn("No pod name resolved for job [{}]", id);
        }

        /*
         * CLEANUP
         */
        cleanup(id);

        long durationMs = System.currentTimeMillis() - start;

        log.info("Job [{}] finished — exit={} duration={}ms",
                id,
                exitCode,
                durationMs);

        return new ExecutionResult(
                stdout,
                stderr,
                exitCode,
                durationMs,
                timedOut
        );
    }

    private boolean isTrue(Integer n) {
        return n != null && n > 0;
    }

    private void cleanup(String id) {

        try {

            batchApi.deleteNamespacedJob(id, namespace)
                    .propagationPolicy("Foreground")
                    .execute();

        } catch (ApiException e) {

            log.warn("Could not delete Job [{}]: {}", id, e.getResponseBody());
        }

        try {

            coreApi.deleteNamespacedConfigMap(id, namespace)
                    .execute();

        } catch (ApiException e) {

            log.warn("Could not delete ConfigMap [{}]: {}", id, e.getResponseBody());
        }
    }
}