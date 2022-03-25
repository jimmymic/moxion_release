package io.moxion.tools.commands;

import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import software.amazon.awssdk.auth.credentials.*;

import software.amazon.awssdk.auth.credentials.internal.ProfileCredentialsUtils;
import software.amazon.awssdk.profiles.Profile;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codecommit.CodeCommitClient;
import software.amazon.awssdk.services.codecommit.model.*;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;

import software.amazon.awssdk.services.codepipeline.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(
    name = "deployRelease",
    description = "Deploy a JIRA release identifier",
    mixinStandardHelpOptions = true)
public class DeployRelease implements Callable<Void> {
    private final static Logger LOG = LoggerFactory.getLogger(DeployRelease.class.getName());


    @CommandLine.Option(names = {"--uat"}, description = "Deploy to UAT and OA-QA")
    private boolean uat;



    @CommandLine.Option(names = {"--mfa"}, required = true, interactive = true, description = "AWS MFA Token Code")
    private String tokenCode;

    @CommandLine.Option(names = {"--production"}, description = "Deploy to production")
    private boolean production;

    @CommandLine.Option(names = {"--id"}, description = "JIRA release id", required = true)
    private String id;


    @CommandLine.Option(names = {"--force"}, description = "Merge pull requests to complete deployment")
    private boolean force;



    private Credentials sharedCredentials;

    @Override
    public Void call() throws Exception {
        Region region = Region.US_WEST_2;
        StsClient stsClient = StsClient.builder()
            .region(region)
            .credentialsProvider(ProfileCredentialsProvider.builder().profileName("moxion-identity").build())
            .build();

        Profile profileFile = ProfileFile.defaultProfileFile().profile("moxion-shared-admin").get();

        AssumeRoleResponse assumeRoleResponse = stsClient.assumeRole(AssumeRoleRequest.builder()
            .tokenCode(tokenCode)
            .serialNumber(profileFile.property("mfa_serial").get())
            .roleArn(profileFile.property("role_arn").get())
            .roleSessionName("moxion-release-shared")
            .build());

        sharedCredentials = assumeRoleResponse.credentials();


        if (uat) {
            LOG.info("Completing UAT/OA-QA releases");
            releaseToEnvironment("stage", "uat");
            releaseToEnvironment("uat", "oa");
        } else if (production) {
            LOG.info("Completing production releases");
            releaseToEnvironment("uat", "prod");
        }


        return null;

    }

    public boolean releaseToEnvironment(String sourceBranch, String destinationBranch){
        try {
            boolean canContinue = true;

            LOG.info("Merging codecommit " + sourceBranch + " -> " + destinationBranch);
            CodeCommitClient codeCommitClient = CodeCommitClient.builder()
                .credentialsProvider(
                    StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsSessionCredentials.create(sharedCredentials.accessKeyId(),sharedCredentials.secretAccessKey(),sharedCredentials.sessionToken())
                    ))
                .region(Region.US_WEST_2)
                .build();

            CreatePullRequestResponse createPullRequestResponse = codeCommitClient.createPullRequest(CreatePullRequestRequest.builder()
                .targets(Target.builder()
                    .repositoryName("moxion_application")
                    .sourceReference(sourceBranch)
                    .destinationReference(destinationBranch)
                    .build())
                .title("Production Release " + id)
                .build());


            LOG.info("Merging release...");
            if (force) {
                MergePullRequestByFastForwardResponse pullRequestByFastForwardResponse = codeCommitClient.mergePullRequestByFastForward(MergePullRequestByFastForwardRequest.builder()
                    .repositoryName("moxion_application")
                    .pullRequestId(createPullRequestResponse.pullRequest().pullRequestId()).build());
            }


            return true;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
