/*
 * Copyright (c) 2011 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.moe.client.directives;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.moe.client.MoeProblem;
import com.google.devtools.moe.client.MoeUserProblem;
import com.google.devtools.moe.client.Ui;
import com.google.devtools.moe.client.Ui.Task;
import com.google.devtools.moe.client.github.GithubAPI.PullRequest;
import com.google.devtools.moe.client.github.GithubClient;
import com.google.devtools.moe.client.github.PullRequestUrl;
import com.google.devtools.moe.client.config.ProjectConfig;
import com.google.devtools.moe.client.config.RepositoryConfig;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.kohsuke.args4j.Option;

/**
 * Extracts branch metadata from a github pull request and uses this information to
 * perform a branch migration, replaying the pull request commits onto the head of
 * the migration target.
 *
 * @see MigrateBranchDirective
 */
public class GithubPullDirective extends Directive {
  @Option(name = "--db", required = true, usage = "Location of MOE database")
  String dbLocation = "";

  @Option(
    name = "--url",
    required = true,
    usage = "The URL of the pull request\n(e.g. https://github.com/google/MOE/pull/32)"
  )
  String url = "";

  private final ProjectConfig config;
  private final GithubClient client;
  private final Ui ui;
  private final MigrateBranchDirective delegate;

  //@Inject
  GithubPullDirective(
      ProjectConfig config, Ui ui, GithubClient client, MigrateBranchDirective delegate) {
    this.config = config;
    this.client = client;
    this.ui = ui;
    this.delegate = delegate;
  }

  @Override
  protected int performDirectiveBehavior() {
    try (Task task =
        ui.newTask("github-import", "Import a github pull-request and stage it in a workspace.")) {
      PullRequest metadata = client.getPullRequest(url);
      if (debug()) {
        // TODO(cgruber) Re-work debug mode into ui messenger when flags are separated from
        // directives
        ui.message("DEBUG: Pull Request Metadata: '%s'", metadata);
      }
      if (metadata.getMerged()) {
        throw new MoeProblem("This pull request has already been merged on github: '%s'", url);
      }
      switch (metadata.getMergeableState()) {
        case clean:
          ui.message("Pull request %s is ready to merge", metadata.getNumber());
          break;
        case unstable:
          ui.message(
              "WARNING: Pull request %s is ready to merge, but GitHub is reporting it as failing. "
                  + "Continuing, but this branch may have problems. ",
              metadata.getNumber());
          break;
        default:
          throw new MoeProblem(
              "Pull request %s is in an indeterminate state. "
                  + "Please please check the pull request status, "
                  + "perform any needed rebase/merge, and re-run",
              metadata.getNumber());
      }

      String repoConfigName = findRepoConfig(config.getRepositories(), metadata);
      ui.message("Using '%s' as the source repository.", repoConfigName);
      int result =
          delegate.performBranchMigration(
              metadata.getHead().getRepo().getOwner().getLogin() + "_" + metadata.getHead().getRef(),
              repoConfigName,
              metadata.getHead().getRef(),
              metadata.getHead().getRepo().getCloneUrl());
      if (delegate.resultDirectory != null) {
        task.keep(delegate.resultDirectory);
      }
      return result;
    }
  }

  /**
   * Finds a repository configuration from among the configured repositories that match the
   * supplied pull request metadata, if any.
   */
  @VisibleForTesting
  static String findRepoConfig(
      final Map<String, RepositoryConfig> repositories, final PullRequest metadata) {
    PullRequestUrl id = PullRequestUrl.create(metadata.getHtmlUrl());
    for (Map.Entry<String, RepositoryConfig> entry : repositories.entrySet()) {
      if (isGithubRepositoryUrl(entry.getValue().getUrl(), id)) {
        return entry.getKey();
      }
    }
    throw new MoeUserProblem() {
      @Override
      public void reportTo(Ui messenger) {
        StringBuilder sb = new StringBuilder();
        sb.append("No configured repository is applicable to this pull request: ");
        sb.append(metadata.getHtmlUrl()).append("\n");
        for (Map.Entry<String, RepositoryConfig> entry : repositories.entrySet()) {
          sb.append("    name: ")
              .append(entry.getKey())
              .append(" url: ")
              .append(entry.getValue().getUrl())
              .append('\n');
        }
        messenger.message(sb.toString());
      }
    };
  }

  /**
   * A regular expression pattern that (roughly) matches the github repository url
   * possibilities, which are generally of three forms:<ul>
   *   <li>http://github.com/foo/bar
   *   <li>https://github.com/foo/bar
   *   <li>git@github.com:foo/bar
   * </ul>
   */
  private static final Pattern GITHUB_URL_PATTERN =
      Pattern.compile(".*github\\.com[:/]([a-zA-Z0-9_-]*)/([a-zA-Z0-9_-]*)(?:\\.git)?$");

  @VisibleForTesting
  static boolean isGithubRepositoryUrl(String url, PullRequestUrl pullRequestUrl) {
    if (url == null) {
      return false;
    }
    Matcher matcher = GITHUB_URL_PATTERN.matcher(url.trim());
    return matcher.matches()
        && matcher.group(1).equals(pullRequestUrl.getOwner())
        && matcher.group(2).equals(pullRequestUrl.getProject());
  }

  /**
   * A module to supply the directive and a description into maps in the graph.
   *
   * <p>Note: This module breaks the pattern due to an eclipse bug that generates an empty
   * dagger factory for this concrete type, when using an
   * {@literal @}{@link javax.inject.Inject} constructor.
   */
  @dagger.Module
  public static class Module implements Directive.Module<GithubPullDirective> {
    private static final String COMMAND = "github_pull";

    @Override
    @Provides
    @IntoMap
    @StringKey(COMMAND)
    public Directive directive(GithubPullDirective directive) {
      return directive;
    }

    // TODO(cgruber,b/27699944) Figure out why dagger breaks in eclipse when using @Inject
    @Provides
    static GithubPullDirective githubPull(
        ProjectConfig config,
        Ui ui,
        GithubClient client,
        MigrateBranchDirective migrateBranchDirective) {
      return new GithubPullDirective(config, ui, client, migrateBranchDirective);
    }

    @Override
    @Provides
    @IntoMap
    @StringKey(COMMAND)
    public String description() {
      return "Migrates the branch underlying a github pull request into a configured repository";
    }
  }
}
