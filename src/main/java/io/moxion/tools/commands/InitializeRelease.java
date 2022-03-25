package io.moxion.tools.commands;

import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@CommandLine.Command(
    name = "initializeRelease",
    description = "Initialize a JIRA release identifier",
    mixinStandardHelpOptions = true)
public class InitializeRelease implements Callable<Void> {


    private final static Logger LOG = LoggerFactory.getLogger(InitializeRelease.class.getName());

    @CommandLine.Option(names = {"--repos"}, description = "Only prepare the following repositories for release")
    private List<String> repos;

    @CommandLine.Option(names = {"--base"}, defaultValue = "stage", description = "Overide base branch name (use for hotfix releases)")
    private String baseBranch;

    @CommandLine.Option(names = {"--id"}, description = "JIRA release id", required = true)
    private String id;

    @Override
    public Void call() throws Exception {
        GitHub github = GitHubBuilder.fromEnvironment().build();

        PagedSearchIterable<GHRepository> repositoryList = github.searchRepositories()
            .org("moxionio")
            .visibility(GHRepository.Visibility.PRIVATE)
            .topic("managed-release")
            .list();
        LOG.info("Found " + repositoryList.getTotalCount() + " repositories for the org.");
        repositoryList.forEach(repository->checkAndCreatePullRequest(repository));
        return null;
    }

    public void checkAndCreatePullRequest(GHRepository repository){
        try {
            if (repos != null && !repos.isEmpty() && repos.stream().noneMatch(repo -> repo.equalsIgnoreCase(repository.getHttpTransportUrl()) ||
                repo.equalsIgnoreCase(repository.getName()))) {
                LOG.info("Skipping repository " + repository.getName() + " as it is not in the supplied option list");
            }
            List<GHPullRequest> pullRequests = repository.getPullRequests(GHIssueState.OPEN);
            Optional<GHPullRequest> pullRequestOptional = pullRequests.stream().filter(ghPullRequest -> ghPullRequest.getLabels().stream().anyMatch(label -> label.getName().equals(id))).findFirst();
            if (pullRequestOptional.isEmpty()) {
                LOG.info("Pull request does not exist for " + repository.getHttpTransportUrl() + "... creating.");
                GHPullRequest ghPullRequest = repository.createPullRequest("Production Release " + id, baseBranch, "prod", "");
                ghPullRequest.addLabels(id);
            } else {
                LOG.info("Pull request already exists for " + repository.getHttpTransportUrl() + " : " + pullRequestOptional.get().getUrl().toExternalForm());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
