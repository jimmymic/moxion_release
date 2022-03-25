package io.moxion.tools.commands;

import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.profiles.Profile;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codecommit.CodeCommitClient;
import software.amazon.awssdk.services.codecommit.model.CreatePullRequestRequest;
import software.amazon.awssdk.services.codecommit.model.CreatePullRequestResponse;
import software.amazon.awssdk.services.codecommit.model.Target;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(
    name = "prepareRelease",
    description = "Prepare a JIRA release identifier",
    mixinStandardHelpOptions = true)
public class PrepareRelease implements Callable<Void> {
    private final static Logger LOG = LoggerFactory.getLogger(PrepareRelease.class.getName());

    @CommandLine.Option(names = {"--mfa"}, required = true, interactive = true, description = "AWS MFA Token Code")
    private String tokenCode;

    @CommandLine.Option(names = {"--force"}, description = "Commit any open pull requests")
    private boolean force;

    @CommandLine.Option(names = {"--id"}, description = "JIRA release id", required = true)
    private String id;

    private Credentials artifactsCredentials;

    @Override
    public Void call() throws Exception {
        Region region = Region.US_WEST_2;
        StsClient stsClient = StsClient.builder()
            .region(region)
            .credentialsProvider(ProfileCredentialsProvider.builder().profileName("moxion-identity").build())
            .build();


        Profile artifactProfileFile = ProfileFile.defaultProfileFile().profile("moxion-artifact-admin").get();

        AssumeRoleResponse assumeRoleResponse = stsClient.assumeRole(AssumeRoleRequest.builder()
            .tokenCode(tokenCode)
            .serialNumber(artifactProfileFile.property("mfa_serial").get())
            .roleArn(artifactProfileFile.property("role_arn").get())
            .roleSessionName("moxion-release-artifacts")
            .build());
        artifactsCredentials = assumeRoleResponse.credentials();

        GitHub github = GitHubBuilder.fromEnvironment().build();
        PagedSearchIterable<GHRepository> repositoryList = github.searchRepositories()
            .org("moxionio")
            .visibility(GHRepository.Visibility.PRIVATE)
            .topic("managed-release").list();
        boolean canContinue = true;
        for (GHRepository repository : repositoryList) {
            canContinue &= validateAndClosePullRequest(repository);
        }

        if (!canContinue) {
            return null;
        }

        if (!validatePipelines()) {
            LOG.info("Please validate any issues above and rerun this process until you no longer receive this message.");
            return null;
        } else {
            LOG.info("No issues, you can now continue to run DeployRelease");
        }


        return null;

    }

    public boolean validateAndClosePullRequest(GHRepository repository) {
        try {
            boolean canContinue = true;
            LOG.info("Validating status of repository: " + repository.getName());
            PagedIterable<GHPullRequest> allPullRequests = repository.queryPullRequests().state(GHIssueState.ALL).sort(GHPullRequestQueryBuilder.Sort.CREATED).direction(GHDirection.DESC).base("prod").list();
            List<GHPullRequest> pullRequests = allPullRequests.withPageSize(100).toList().stream().filter(ghPullRequest -> ghPullRequest.getLabels().stream().anyMatch(label -> label.getName().equals(id))).collect(Collectors.toList());
            for (GHPullRequest ghPullRequest : pullRequests) {
                if (ghPullRequest.isMerged()) {
                    LOG.info("PR " + ghPullRequest.getUrl() + " is merged");
                    canContinue &= true;
                } else if (ghPullRequest.isDraft()) {
                    LOG.info("PR " + ghPullRequest.getUrl() + " is draft, please remove or close");
                    canContinue &= false;
                } else if (!ghPullRequest.getMergeable()) {
                    LOG.info("PR " + ghPullRequest.getUrl() + " is not able to be merged, please validate status");
                    canContinue &= false;
                } else if (force) {
                    LOG.info("PR " + ghPullRequest.getUrl() + " is able to be merged, merging. Rerun this process to validate successful merge");
                    ghPullRequest.merge(null);
                    canContinue &= false;
                } else {
                    LOG.info("PR " + ghPullRequest.getUrl() + " is not merged. Please manually merge or force merge using this tool and rerun this process to validate successful merge");
                    canContinue &= false;
                }
            }

            if (!canContinue) {
                LOG.info("Please fix and validate issues and rerun this process");
            } else {
                LOG.info("No issues, continuing");
            }

            return canContinue;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public boolean validatePipelines() throws IOException {
        boolean canContinue = true;
        LOG.info("Validating pipelines match expected commit IDs");
        CodePipelineClient codePipelineClient = CodePipelineClient.builder()
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsSessionCredentials.create(artifactsCredentials.accessKeyId(), artifactsCredentials.secretAccessKey(), artifactsCredentials.sessionToken())
                ))
            .region(Region.US_WEST_2)
            .build();

        ListPipelinesResponse listPipelinesResponse = codePipelineClient.listPipelines();
        if (!listPipelinesResponse.hasPipelines()) {
            throw new RuntimeException("Issue retrieving pipelines");
        }
        GitHub github = GitHubBuilder.fromEnvironment().build();

        // get list of prod pipelines
        List<PipelineSummary> pipelineSummaryList = listPipelinesResponse.pipelines().stream().filter(pipelineSummary -> pipelineSummary.name().toLowerCase().endsWith("-prod")).collect(Collectors.toList());
        for (PipelineSummary pipelineSummary : pipelineSummaryList) {
            PipelineDeclaration pipelineDeclaration = codePipelineClient.getPipeline(GetPipelineRequest.builder().name(pipelineSummary.name()).build()).pipeline();
            for (StageDeclaration stageDeclaration : pipelineDeclaration.stages()) {
                for (ActionDeclaration actionDeclaration : stageDeclaration.actions()) {
                    if (actionDeclaration.actionTypeId().category() == ActionCategory.SOURCE && actionDeclaration.actionTypeId().provider().equals("GitHub")) {
                        Map<String, String> configuration = actionDeclaration.configuration();
                        GHRepository ghRepository = github.getRepository("moxionio/" + configuration.get("Repo"));
                        if (ghRepository.listTopics().stream().noneMatch(a -> a.equals("managed-release"))) {
                            LOG.info("Skipping repository " + ghRepository.getHttpTransportUrl() + " as it is not a managed-release repository");
                        } else {
                            canContinue &= validatePipelineMatchesRepository(codePipelineClient, pipelineSummary, ghRepository);
                        }
                    }
                }
            }
        }
        return canContinue;
    }



    private boolean validatePipelineMatchesRepository(CodePipelineClient codePipelineClient, PipelineSummary pipelineSummary, GHRepository ghRepository) throws IOException {
        ListPipelineExecutionsResponse listPipelineExecutionsResponse = codePipelineClient.listPipelineExecutions(ListPipelineExecutionsRequest.builder().pipelineName(pipelineSummary.name()).maxResults(1).build());

        Optional<PipelineExecutionSummary> pipelineExecutionSummary = listPipelineExecutionsResponse.pipelineExecutionSummaries().stream().findFirst();
        if (!pipelineExecutionSummary.isPresent()) {
            LOG.info("No pipeline executions, this is not correct. Please validate the pipeline and restart the process");
            return false;
        } else {
            for (SourceRevision sourceRevision : pipelineExecutionSummary.get().sourceRevisions()) {
                LOG.info("Source Revision URL: " + sourceRevision.revisionUrl() + " at " + sourceRevision.revisionId());
                GHBranch ghBranch = ghRepository.getBranch("prod");
                if (ghBranch.getSHA1().equalsIgnoreCase(sourceRevision.revisionId())) {
                    LOG.info("Pipeline revision matches the prod commit ID");
                } else {
                    LOG.info("Pipeline revision does not match the prod commit ID");
                    return false;

                }
            }
        }

        if (pipelineExecutionSummary.get().status() == PipelineExecutionStatus.IN_PROGRESS) {
            LOG.info("Pipeline " + pipelineSummary.name() + " is currently executing, please wait for this to complete before retrying the process.");
            return false;
        } else if (pipelineExecutionSummary.get().status() == PipelineExecutionStatus.SUCCEEDED) {
            LOG.info("Pipeline " + pipelineSummary.name() + " has succeeded.");
            return true;
        } else {
            LOG.info("Pipeline " + pipelineSummary.name() + " is currently " + pipelineExecutionSummary.get().status() + ", please resolve any issues before retrying the process.");
            return false;
        }
    }

}
