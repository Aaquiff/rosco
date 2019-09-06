package com.netflix.spinnaker.rosco.manifests.helm;

import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import com.netflix.spinnaker.rosco.api.BakeStatus;
import com.netflix.spinnaker.rosco.jobs.BakeRecipe;
import com.netflix.spinnaker.rosco.jobs.JobExecutor;
import com.netflix.spinnaker.rosco.jobs.JobRequest;
import com.netflix.spinnaker.rosco.manifests.BakeManifestRequest;
import com.netflix.spinnaker.rosco.manifests.TemplateUtils.BakeManifestEnvironment;
import com.netflix.spinnaker.security.AuthenticatedRequest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HelmBakeManifestService {
  @Autowired HelmTemplateUtils helmTemplateUtils;

  @Autowired JobExecutor jobExecutor;

  HelmTemplateUtils templateUtils(HelmBakeManifestRequest request) {
    BakeManifestRequest.TemplateRenderer templateRenderer = request.getTemplateRenderer();
    if (templateRenderer == null) {
      throw new IllegalArgumentException("The request type must be set (e.g. helm2).");
    }

    switch (templateRenderer) {
      case HELM2:
        return helmTemplateUtils;
      default:
        throw new IllegalArgumentException(
            "Request type " + templateRenderer + " is not supported.");
    }
  }

  public Artifact bake(HelmBakeManifestRequest request) {
    HelmTemplateUtils utils = templateUtils(request);
    BakeManifestEnvironment env = new BakeManifestEnvironment();
    BakeRecipe recipe = utils.buildBakeRecipe(env, request);
    BakeStatus bakeStatus;

    try {
      JobRequest jobRequest =
          new JobRequest(
              recipe.getCommand(),
              new ArrayList<>(),
              UUID.randomUUID().toString(),
              AuthenticatedRequest.getSpinnakerExecutionId().orElse(null),
              false);
      String jobId = jobExecutor.startJob(jobRequest);

      bakeStatus = jobExecutor.updateJob(jobId);

      while (bakeStatus == null || bakeStatus.getState() == BakeStatus.State.RUNNING) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }

        bakeStatus = jobExecutor.updateJob(jobId);
      }

      if (bakeStatus.getResult() != BakeStatus.Result.SUCCESS) {
        throw new IllegalStateException(
            "Bake of " + request + " failed: " + bakeStatus.getLogsContent());
      }
    } finally {
      env.cleanup();
    }

    return Artifact.builder()
        .type("embedded/base64")
        .name(request.getOutputArtifactName())
        .reference(Base64.getEncoder().encodeToString(removeTestsDirectoryTemplates(bakeStatus.getOutputContent()).getBytes()))
        .build();
  }

  public String removeTestsDirectoryTemplates(String input) {
    String manifestSeperator = "---";

    ArrayList<String> inputManifests = new ArrayList<String>();
    Collections.addAll(inputManifests, input.split(manifestSeperator));

    final List<String> outputManifests =
            inputManifests.stream()
                    .filter(
                            manifest -> !manifest.trim().isEmpty() && !manifest.contains("/templates/tests"))
                    .collect(Collectors.toList());

    StringBuilder stringBuilder = new StringBuilder();
    outputManifests.stream()
            .forEach(manifest -> stringBuilder.append(manifestSeperator).append(manifest));
    return stringBuilder.toString();
  }
}
