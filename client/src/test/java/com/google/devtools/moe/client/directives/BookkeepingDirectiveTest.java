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

import static com.google.common.truth.Truth.assertThat;
import static org.easymock.EasyMock.expect;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.moe.client.CommandRunner;
import com.google.devtools.moe.client.FileSystem;
import com.google.devtools.moe.client.Lifetimes;
import com.google.devtools.moe.client.MoshiModule;
import com.google.devtools.moe.client.Ui;
import com.google.devtools.moe.client.codebase.ExpressionEngine;
import com.google.devtools.moe.client.database.Bookkeeper;
import com.google.devtools.moe.client.database.Db;
import com.google.devtools.moe.client.database.DbStorage;
import com.google.devtools.moe.client.database.FileDb;
import com.google.devtools.moe.client.database.RepositoryEquivalence;
import com.google.devtools.moe.client.database.SubmittedMigration;
import com.google.devtools.moe.client.project.ProjectContext;
import com.google.devtools.moe.client.repositories.Repositories;
import com.google.devtools.moe.client.repositories.RepositoryType;
import com.google.devtools.moe.client.repositories.Revision;
import com.google.devtools.moe.client.testing.DummyRepositoryFactory;
import com.google.devtools.moe.client.testing.InMemoryFileSystem;
import com.google.devtools.moe.client.testing.InMemoryProjectContextFactory;
import com.google.devtools.moe.client.testing.TestingUtils;
import com.google.devtools.moe.client.tools.CodebaseDiffer;
import com.google.devtools.moe.client.tools.FileDifference.ConcreteFileDiffer;
import com.google.devtools.moe.client.tools.FileDifference.FileDiffer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import junit.framework.TestCase;
import org.easymock.EasyMock;
import org.easymock.IMocksControl;

/**
 * Tests for {@link BookkeepingDirective}. DummyRepository is used, and per the bookkeeping flow,
 * dummy codebases are diffed at head (internal and public revision "1") and at one migrated
 * revision (internal "migrated_from" and public "migrated_to"). Note that equivalence of these
 * codebases -- /dummy/codebase/{int,pub}/1 and
 * /dummy/codebase/{int/migrated_from,pub/migrated_to} -- is implicitly determined by
 * existence/nonexistence of corresponding files in the {@code FileSystem} and <em>not</em> by the
 * output of the "diff" command, which is merely stubbed in.
 */
public class BookkeepingDirectiveTest extends TestCase {
  private static final File DB_FILE = new File("/path/to/db");
  private final ByteArrayOutputStream stream = new ByteArrayOutputStream();
  private final IMocksControl control = EasyMock.createControl();
  private final CommandRunner cmd = control.createMock(CommandRunner.class);
  private final DbStorage storage = new DbStorage();

  private static InMemoryProjectContextFactory init(InMemoryProjectContextFactory contextFactory)
      throws Exception {
    contextFactory.projectConfigs.put(
        "moe_config.txt",
        "{\"name\":\"foo\",\"repositories\":{"
            + "\"int\":{\"type\":\"dummy\",\"project_space\":\"internal\"},"
            + "\"pub\":{\"type\":\"dummy\"}},"
            + "\"translators\":[{\"from_project_space\":\"internal\","
            + "\"to_project_space\":\"public\",\"steps\":[{\"name\":\"id_step\","
            + "\"editor\":{\"type\":\"identity\"}}]}],"
            + "\"migrations\":[{\"name\":\"test\",\"from_repository\":\"int\","
            + "\"to_repository\":\"pub\"}]}");
    return contextFactory;
  }

  private void expectDiffs() throws Exception {
    // updateCompletedMigrations
    ImmutableList<String> args =
        ImmutableList.of(
            "-N",
            "-u",
            "/dummy/codebase/int/migrated_from/file",
            "/dummy/codebase/pub/migrated_to/file");
    expect(cmd.runCommand("", "diff", args)).andReturn("unused");

    // updateHeadEquivalence
    args = ImmutableList.of("-N", "-u", "/dummy/codebase/int/1/file", "/dummy/codebase/pub/1/file");
    expect(cmd.runCommand("", "diff", args)).andReturn("unused");
  }

  /**
   * Bookkeeping for codebases the same at head, different at migrated revs.
   */
  public void testHeadsEquivalent() throws Exception {

    ImmutableMap<String, String> files =
        ImmutableMap.of(
            DB_FILE.getPath(),
            "{\"equivalences\":[], \"migrations\":[]}",
            "/dummy/codebase/int/1/file",
            "1",
            "/dummy/codebase/pub/1/file",
            "1 (equivalent)",
            "/dummy/codebase/int/migrated_from/file",
            "migrated_from",
            "/dummy/codebase/pub/migrated_to/",
            "dir (different)");
    FileSystem filesystem = new InMemoryFileSystem(files, new Lifetimes(new Ui(System.err)));
    FileDiffer fileDiffer = new ConcreteFileDiffer(cmd, filesystem);
    CodebaseDiffer codebaseDiffer = new CodebaseDiffer(fileDiffer, filesystem);
    Repositories repositories =
        new Repositories(
            ImmutableSet.<RepositoryType.Factory>of(new DummyRepositoryFactory()));
    Ui ui = new Ui(stream, filesystem);
    ExpressionEngine expressionEngine = TestingUtils.expressionEngineWithRepo(ui, filesystem, cmd);
    InMemoryProjectContextFactory contextFactory =
        init(new InMemoryProjectContextFactory(expressionEngine, ui, repositories));
    ProjectContext context = contextFactory.create("moe_config.txt");
    Db db =
        new FileDb(
            DB_FILE.getPath(), storage, new FileDb.Writer(MoshiModule.provideMoshi(), filesystem));
    BookkeepingDirective d =
        new BookkeepingDirective(new Bookkeeper(context, codebaseDiffer, db, ui, expressionEngine));
    d.dbLocation = DB_FILE.getAbsolutePath();

    expect(
            cmd.runCommand(
                "",
                "diff",
                ImmutableList.of(
                    "-N", "-u", "/dummy/codebase/int/1/file", "/dummy/codebase/pub/1/file")))
        .andReturn("unused");

    control.replay();
    assertEquals(0, d.perform());
    control.verify();

    // expected db at end of call to bookkeep
    DbStorage expectedDb = new DbStorage();
    expectedDb.addEquivalence(
        new RepositoryEquivalence(new Revision(1, "int"), new Revision(1, "pub")));

    assertThat(storage).isEqualTo(expectedDb);
  }

  /**
   * Bookkeeping for codebases different at head and migrated revs.
   */
  public void testOneSubmittedMigration_nonEquivalent() throws Exception {
    ImmutableMap<String, String> files =
        ImmutableMap.of(
            "/path/to/db", "{\"equivalences\":[], \"migrations\":[]}",
            "/dummy/codebase/int/1/file", "1",
            "/dummy/codebase/pub/1/", "empty dir (different)",
            "/dummy/codebase/int/migrated_from/file", "migrated_from",
            "/dummy/codebase/pub/migrated_to/", "empty dir (different)");
    FileSystem filesystem = new InMemoryFileSystem(files, new Lifetimes(new Ui(System.err)));
    FileDiffer fileDiffer = new ConcreteFileDiffer(cmd, filesystem);
    CodebaseDiffer codebaseDiffer = new CodebaseDiffer(fileDiffer, filesystem);
    Repositories repositories =
        new Repositories(
            ImmutableSet.<RepositoryType.Factory>of(new DummyRepositoryFactory()));
    Ui ui = new Ui(stream, filesystem);
    ExpressionEngine expressionEngine = TestingUtils.expressionEngineWithRepo(ui, filesystem, cmd);
    InMemoryProjectContextFactory contextFactory =
        init(new InMemoryProjectContextFactory(expressionEngine, ui, repositories));
    ProjectContext context = contextFactory.create("moe_config.txt");
    Db db =
        new FileDb(
            DB_FILE.getPath(), storage, new FileDb.Writer(MoshiModule.provideMoshi(), filesystem));
    BookkeepingDirective d =
        new BookkeepingDirective(new Bookkeeper(context, codebaseDiffer, db, ui, expressionEngine));
    d.dbLocation = DB_FILE.getAbsolutePath();

    expectDiffs();

    control.replay();
    assertEquals(0, d.perform());
    control.verify();

    // expected db at end of call to bookkeep
    DbStorage expectedDb = new DbStorage();
    expectedDb.addMigration(
        new SubmittedMigration(
            new Revision("migrated_from", "int"), new Revision("migrated_to", "pub")));

    assertThat(storage).isEqualTo(expectedDb);
  }

  /**
   * Bookkeeping for codebases different at head and equivalent at migrated revs.
   */
  public void testOneSubmittedMigration_equivalent() throws Exception {
    ImmutableMap<String, String> files =
        ImmutableMap.of(
            "/path/to/db", "{\"equivalences\":[], \"migrations\":[]}",
            "/dummy/codebase/int/1/file", "1",
            "/dummy/codebase/pub/1/", "empty dir (different)",
            "/dummy/codebase/int/migrated_from/file", "migrated_from",
            "/dummy/codebase/pub/migrated_to/file", "migrated_to (equivalent)");
    FileSystem filesystem = new InMemoryFileSystem(files, new Lifetimes(new Ui(System.err)));
    FileDiffer fileDiffer = new ConcreteFileDiffer(cmd, filesystem);
    CodebaseDiffer codebaseDiffer = new CodebaseDiffer(fileDiffer, filesystem);
    Repositories repositories =
        new Repositories(
            ImmutableSet.<RepositoryType.Factory>of(new DummyRepositoryFactory()));
    Ui ui = new Ui(stream, filesystem);
    ExpressionEngine expressionEngine = TestingUtils.expressionEngineWithRepo(ui, filesystem, cmd);
    InMemoryProjectContextFactory contextFactory =
        init(new InMemoryProjectContextFactory(expressionEngine, ui, repositories));
    ProjectContext context = contextFactory.create("moe_config.txt");
    Db db =
        new FileDb(
            DB_FILE.getPath(), storage, new FileDb.Writer(MoshiModule.provideMoshi(), filesystem));
    BookkeepingDirective d =
        new BookkeepingDirective(new Bookkeeper(context, codebaseDiffer, db, ui, expressionEngine));
    d.dbLocation = DB_FILE.getAbsolutePath();

    expectDiffs();

    control.replay();
    assertEquals(0, d.perform());
    control.verify();

    // expected db at end of call to bookkeep
    DbStorage expectedDb = new DbStorage();
    expectedDb.addEquivalence(
        new RepositoryEquivalence(
            new Revision("migrated_from", "int"), new Revision("migrated_to", "pub")));
    expectedDb.addMigration(
        new SubmittedMigration(
            new Revision("migrated_from", "int"), new Revision("migrated_to", "pub")));

    assertThat(storage).isEqualTo(expectedDb);
  }
}
