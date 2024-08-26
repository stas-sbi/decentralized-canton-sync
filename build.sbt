// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import BuildUtil.runCommand
import Dependencies.*
import DamlPlugin.autoImport.*
import BuildCommon.defs.*
import java.io.ByteArrayInputStream
import scala.reflect.io.Streamable
import java.nio
import scala.jdk.CollectionConverters.*
import sbtassembly.{MergeStrategy, PathList}

/*
 * sbt-settings that will be shared between all CN apps.
 */

BuildCommon.sbtSettings

// sbt insists on these re-declarations
lazy val `canton-community-app` = BuildCommon.`canton-community-app`
lazy val `canton-community-app-base` = BuildCommon.`canton-community-app-base`
lazy val `canton-community-base` = BuildCommon.`canton-community-base`
lazy val `canton-community-common` = BuildCommon.`canton-community-common`
lazy val `canton-community-domain` = BuildCommon.`canton-community-domain`
lazy val `canton-community-participant` = BuildCommon.`canton-community-participant`
lazy val `canton-community-admin-api` = BuildCommon.`canton-community-admin-api`
lazy val `canton-community-integration-testing` = BuildCommon.`canton-community-integration-testing`
lazy val `canton-community-testing` = BuildCommon.`canton-community-testing`
lazy val `canton-blake2b` = BuildCommon.`canton-blake2b`
lazy val `canton-slick-fork` = BuildCommon.`canton-slick-fork`
lazy val `canton-wartremover-extension` = BuildCommon.`canton-wartremover-extension`
lazy val `canton-util-external` = BuildCommon.`canton-util-external`
lazy val `canton-util-internal` = BuildCommon.`canton-util-internal`
lazy val `canton-util-logging` = BuildCommon.`canton-util-logging`
lazy val `canton-pekko-fork` = BuildCommon.`canton-pekko-fork`
lazy val `canton-ledger-common` = BuildCommon.`canton-ledger-common`
lazy val `canton-ledger-api-core` = BuildCommon.`canton-ledger-api-core`
lazy val `canton-ledger-json-api` = BuildCommon.`canton-ledger-json-api`
lazy val `canton-daml-errors` = BuildCommon.`canton-daml-errors`
lazy val `canton-ledger-api` = BuildCommon.`canton-ledger-api`
lazy val `canton-bindings-java` = BuildCommon.`canton-bindings-java`
lazy val `canton-google-common-protos-scala` = BuildCommon.`canton-google-common-protos-scala`
lazy val `canton-sequencer-driver-api` = BuildCommon.`canton-sequencer-driver-api`
lazy val `canton-community-reference-driver` = BuildCommon.`canton-community-reference-driver`

lazy val `splice-wartremover-extension` = Wartremover.`splice-wartremover-extension`

inThisBuild(
  List(
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
  )
)

val allDarsFilter = ScopeFilter(inAnyProject, inConfigurations(Compile), inTasks(damlBuild))

/*
 * Root project
 */
lazy val root: Project = (project in file("."))
  .aggregate(
    `apps-common`,
    `apps-common-sv`,
    `apps-validator`,
    `apps-scan`,
    `apps-splitwell`,
    `apps-sv`,
    `apps-app`,
    `apps-wallet`,
    `apps-frontends`,
    `splice-util-daml`,
    `splice-amulet-daml`,
    `splice-amulet-test-daml`,
    `splice-amulet-name-service-daml`,
    `splice-amulet-name-service-test-daml`,
    `splice-wallet-payments-daml`,
    `splice-wallet-daml`,
    `splice-wallet-test-daml`,
    `splitwell-daml`,
    `splitwell-test-daml`,
    `splice-dso-governance-daml`,
    `splice-dso-governance-test-daml`,
    `splice-validator-lifecycle-daml`,
    `splice-validator-lifecycle-test-daml`,
    `splice-app-manager-daml`,
    `build-tools-dar-lock-checker`,
    `canton-community-base`,
    `canton-community-common`,
    `canton-community-integration-testing`,
    `canton-community-testing`,
    `canton-blake2b`,
    `canton-slick-fork`,
    `canton-wartremover-extension`,
    `canton-community-app`,
    `canton-community-app-base`,
    `canton-community-domain`,
    `canton-community-participant`,
    `canton-ledger-common`,
    `canton-ledger-api-core`,
    `canton-ledger-api`,
    `canton-bindings-java`,
    `canton-google-common-protos-scala`,
    pulumi,
    `load-tester`,
    tools,
    `splice-wartremover-extension`,
    docs,
  )
  .settings(
    BuildCommon.sharedSettings,
    scalacOptions += "-Wconf:src=src_managed/.*:silent",
    // Needed to be able to resolve scalafmt snapshot versions
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    damlDarsLockCheckerFileArg := {
      val darFiles: Seq[File] = damlBuild.all(allDarsFilter).value.flatten
      val basePath = baseDirectory.value.toPath
      val cantonPath = basePath.resolve("canton")
      val darPaths = for {
        file <- darFiles
        path = file.toPath
        if !path.startsWith(cantonPath)
      } yield basePath.relativize(path)
      val outputFile = "daml/dars.lock"
      " " + (Seq(outputFile) ++ darPaths ++ getCommittedDarFiles).mkString(" ")
    },
    damlDarsLockFileUpdate :=
      Def.taskDyn {
        (`build-tools-dar-lock-checker` / Compile / run)
          .toTask(" update" + damlDarsLockCheckerFileArg.value)
      }.value,
    damlDarsLockFileCheck :=
      Def.taskDyn {
        (`build-tools-dar-lock-checker` / Compile / run)
          .toTask(" check" + damlDarsLockCheckerFileArg.value)
      }.value,
    Headers.OtherHeaderSettings,
  )

val damlDarsLockFileCheck = taskKey[Unit]("Check the daml/dars.lock file")
val damlDarsLockFileUpdate = taskKey[Unit]("Update the daml/dars.lock file")
val damlDarsLockCheckerFileArg =
  taskKey[String]("Argument line for updating the daml/dars.lock file")

lazy val `build-tools-dar-lock-checker` = project
  .in(file("build-tools/dar-lock-checker"))
  .settings(
    libraryDependencies ++= Seq(Dependencies.better_files, Dependencies.daml_lf_archive_reader),
    Headers.ApacheDAHeaderSettings,
  )

lazy val `tools` = project
  .in(file("apps/tools"))
  .dependsOn(`apps-app` % "compile->test")
  .settings(
    libraryDependencies += auth0,
    Headers.ApacheDAHeaderSettings,
  )

lazy val docs = project
  .in(file("docs"))
  .dependsOn(`apps-common`)
  .settings(
    Compile / resourceGenerators += Def.task {
      val baseDir = baseDirectory.value
      val srcDir = sourceDirectory.value
      val log = streams.value.log
      val cacheDir = streams.value.cacheDirectory
      val cache = FileFunction.cached(cacheDir) { _ =>
        runCommand(
          Seq("./gen-daml-docs.sh"),
          log,
          None,
          Some(baseDir),
        )
        Set(srcDir / "app_dev" / "api")
      }
      val damlSources =
        (`splice-app-manager-daml` / Compile / damlBuild).value ++
          (`splice-amulet-daml` / Compile / damlBuild).value ++
          (`splice-amulet-name-service-daml` / Compile / damlBuild).value ++
          (`splitwell-daml` / Compile / damlBuild).value ++
          (`splice-dso-governance-daml` / Compile / damlBuild).value ++
          (`splice-validator-lifecycle-daml` / Compile / damlBuild).value ++
          (`splice-wallet-daml` / Compile / damlBuild).value ++
          (`splice-wallet-payments-daml` / Compile / damlBuild).value
      cache(
        damlSources.toSet
      ).toSeq
    }.taskValue,
    bundle := {
      (Compile / resources).value
      val baseDir = baseDirectory.value
      val srcDir = sourceDirectory.value
      val outDir = baseDirectory.value / "html"
      val log = streams.value.log
      val version = BuildUtil.runCommandOptionalLog(Seq("./build-tools/get-snapshot-version"))
      val cacheDir = streams.value.cacheDirectory
      val cache = FileFunction.cached(cacheDir) { _ =>
        runCommand(
          Seq(
            "sphinx-build",
            "-M",
            "html",
            srcDir.getPath,
            outDir.getPath,
            "-D",
            s"version=$version",
            "-W",
          ),
          log,
          None,
          Some(baseDir),
          extraEnv = Seq(
            ("VERSION", version)
          ),
        )
        org.apache.commons.io.FileUtils.deleteDirectory(outDir / "doctrees")
        Set(outDir)
      }
      (
        outDir,
        cache(
          (srcDir ** "*").get.toSet
        ),
      )
    },
    cleanFiles += baseDirectory.value / "html",
    cleanFiles += sourceDirectory.value / "app_dev" / "api",
    Headers.ApacheDAHeaderSettings,
  )

// Shared non-template/non-interface code
// used across our DARs.
lazy val `splice-util-daml` =
  project
    .in(file("daml/splice-util"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings
    )
    .dependsOn(
      `canton-bindings-java`
    )

lazy val `splice-amulet-daml` =
  project
    .in(file("daml/splice-amulet"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`splice-util-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splice-amulet-test-daml` =
  project
    .in(file("daml/splice-amulet-test"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`splice-amulet-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splice-dso-governance-daml` =
  project
    .in(file("daml/splice-dso-governance"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`splice-util-daml` / Compile / damlBuild).value ++
          (`splice-amulet-daml` / Compile / damlBuild).value ++
          (`splice-amulet-name-service-daml` / Compile / damlBuild).value ++
          (`splice-wallet-payments-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splice-dso-governance-test-daml` =
  project
    .in(file("daml/splice-dso-governance-test"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`splice-util-daml` / Compile / damlBuild).value ++
          (`splice-amulet-test-daml` / Compile / damlBuild).value ++
          (`splice-amulet-name-service-test-daml` / Compile / damlBuild).value ++
          (`splice-dso-governance-daml` / Compile / damlBuild).value ++
          (`splice-wallet-payments-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splice-validator-lifecycle-daml` =
  project
    .in(file("daml/splice-validator-lifecycle"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`splice-util-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splice-validator-lifecycle-test-daml` =
  project
    .in(file("daml/splice-validator-lifecycle-test"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`splice-util-daml` / Compile / damlBuild).value ++ (`splice-validator-lifecycle-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

// This defines the Daml model that we expose to app developers
// to manage payments through the wallet.
lazy val `splice-wallet-payments-daml` =
  project
    .in(file("daml/splice-wallet-payments"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`splice-util-daml` / Compile / damlBuild).value ++
          (`splice-amulet-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

// This defines the Daml model that we do not expose to app devs
// but do use internally, e.g., for batching.
lazy val `splice-wallet-daml` =
  project
    .in(file("daml/splice-wallet"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`splice-amulet-daml` / Compile / damlBuild).value ++ (`splice-wallet-payments-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splice-wallet-test-daml` =
  project
    .in(file("daml/splice-wallet-test"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`splice-amulet-test-daml` / Compile / damlBuild).value ++ (`splice-wallet-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splice-amulet-name-service-daml` =
  project
    .in(file("daml/splice-amulet-name-service"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`splice-wallet-payments-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splice-amulet-name-service-test-daml` =
  project
    .in(file("daml/splice-amulet-name-service-test"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`splice-wallet-test-daml` / Compile / damlBuild).value ++ (`splice-amulet-test-daml` / Compile / damlBuild).value ++ (`splice-amulet-name-service-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splitwell-daml` =
  project
    .in(file("daml/splitwell"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`splice-wallet-payments-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splitwell-test-daml` =
  project
    .in(file("daml/splitwell-test"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`splice-wallet-test-daml` / Compile / damlBuild).value ++ (`splitwell-daml` / Compile / damlBuild).value,
    )
    .dependsOn(`canton-bindings-java`)

lazy val `splice-app-manager-daml` =
  project
    .in(file("daml/splice-app-manager"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings
    )
    .dependsOn(`canton-bindings-java`)

lazy val `apps-common` =
  project
    .in(file("apps/common"))
    .dependsOn(
      `canton-bindings-java` % "test->test",
      `canton-community-common`,
      `canton-community-app` % "compile->compile;test->test",
      `canton-community-testing` % "test",
      `splice-wartremover-extension` % "compile->compile;test->test",
      // We include all DARs here to make sure they are available as resources.
      `splice-app-manager-daml`,
      `splice-app-manager-daml`,
      `splice-amulet-daml`,
      `splice-amulet-name-service-daml`,
      `splitwell-daml`,
      `splice-dso-governance-daml`,
      `splice-validator-lifecycle-daml`,
      `splice-wallet-daml`,
      `splice-wallet-payments-daml`,
    )
    .enablePlugins(BuildInfoPlugin)
    .settings(
      libraryDependencies ++= Seq(
        google_cloud_storage,
        kubernetes_client,
        Dependencies.daml_lf_validation,
        scalatestScalacheck % Test,
        scalapb_runtime_grpc,
        scalapb_runtime,
        scalapb_json4,
        java_jwt,
        jwks_rsa,
        spray_json,
        pekko_spray_json,
        Dependencies.parallel_collections,
      ),
      BuildCommon.sharedAppSettings,
      buildInfoKeys := Seq[BuildInfoKey](
        BuildInfoKey(
          "compiledVersion",
          BuildUtil.runCommandOptionalLog(Seq("./build-tools/get-snapshot-version")),
        ),
        BuildInfoKey(
          "commitUnixTimestamp",
          BuildUtil.runCommandOptionalLog(Seq("git", "show", "-s", "--format=%ct", "HEAD")),
        ),
        BuildInfoKey(
          "compatibleVersion",
          better.files.File("LATEST_RELEASE").contentAsString.strip,
        ),
      ),
      buildInfoPackage := "com.daml.network.environment",
      buildInfoObject := "BuildInfo",
      Compile / guardrailTasks :=
        List("external", "internal").flatMap { scope =>
          List(
            ScalaServer(
              new File(s"apps/common/src/main/openapi/common-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              modules = List("pekko-http-v1.0.0", "circe"),
              customExtraction = true,
            ),
            ScalaClient(
              new File(s"apps/common/src/main/openapi/common-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              modules = List("pekko-http-v1.0.0", "circe"),
            ),
          )
        },
    )

lazy val `apps-common-sv` =
  project
    .in(file("apps/common/sv"))
    .dependsOn(
      `apps-common`
    )
    .settings(
      BuildCommon.sharedAppSettings,
      Compile / guardrailTasks := List(
        ScalaClient(
          new File(s"apps/sv/src/main/openapi/sv-internal.yaml"),
          pkg = "com.daml.network.http.v0",
          modules = List("pekko-http-v1.0.0", "circe"),
        )
      ),
    )

lazy val `apps-validator` =
  project
    .in(file("apps/validator"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-common-sv`,
      `apps-scan` % "compile->compile;test->test",
      `splice-wallet-daml`,
      `apps-wallet`,
      `splice-app-manager-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(pekko_http_cors, commons_compress, jaxb_abi),
      BuildCommon.sharedAppSettings,
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "validator-openapi",
        openApiSpec = "validator-internal.yaml",
      ),
      BuildCommon.TS.openApiSettings(
        npmName = "ans-external-openapi",
        openApiSpec = "ans-external.yaml",
        directory = "external-openapi-ts-client",
      ),
      BuildCommon.TS.openApiSettings(
        npmName = "scan-proxy-openapi",
        openApiSpec = "scan-proxy.yaml",
        directory = "scan-proxy-openapi-ts-client",
      ),
      Compile / guardrailTasks :=
        List("validator-internal", "json-api-proxy-internal", "ans-external", "scan-proxy").flatMap(
          api =>
            List(
              ScalaServer(
                new File(s"apps/validator/src/main/openapi/${api}.yaml"),
                pkg = "com.daml.network.http.v0",
                modules = List("pekko-http-v1.0.0", "circe"),
                customExtraction = true,
              ),
              ScalaClient(
                new File(s"apps/validator/src/main/openapi/${api}.yaml"),
                pkg = "com.daml.network.http.v0",
                modules = List("pekko-http-v1.0.0", "circe"),
              ),
            )
        ),
    )

lazy val `apps-sv` =
  project
    .in(file("apps/sv"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan`,
      `apps-common-sv`,
      `splice-validator-lifecycle-daml`,
      `splice-dso-governance-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(
        pekko_http_cors,
        scalapb_runtime,
        comet_bft_proto,
      ),
      BuildCommon.sharedAppSettings,
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "sv-openapi",
        openApiSpec = "sv-internal.yaml",
      ),
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/sv/src/main/openapi/sv-internal.yaml"),
            pkg = "com.daml.network.http.v0",
            modules = List("pekko-http-v1.0.0", "circe"),
            customExtraction = true,
          )
        ),
    )

lazy val `apps-scan` =
  project
    .in(file("apps/scan"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `splice-dso-governance-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(pekko_http_cors, scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "scan-external-openapi",
        openApiSpec = "scan-external.yaml",
        directory = "external-openapi-ts-client",
      ),
      BuildCommon.TS.openApiSettings(
        npmName = "scan-openapi",
        openApiSpec = "scan-internal.yaml",
      ),
      Compile / guardrailTasks :=
        List("external", "internal").flatMap { scope =>
          List(
            ScalaServer(
              new File(s"apps/scan/src/main/openapi/scan-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              modules = List("pekko-http-v1.0.0", "circe"),
              customExtraction = true,
            ),
            ScalaClient(
              new File(s"apps/scan/src/main/openapi/scan-$scope.yaml"),
              modules = List("pekko-http-v1.0.0", "circe"),
              pkg = "com.daml.network.http.v0",
            ),
          ),
        },
    )

lazy val `apps-common-frontend` = {
  project
    .in(file("apps/common/frontend"))
    .dependsOn(
      `apps-common`,
      `apps-wallet`,
      `apps-splitwell`,
      `apps-validator`,
    )
    .settings(
      // daml typescript code generation settings:
      damlTsCodegenSources :=
        (`splice-amulet-daml` / Compile / damlBuild).value ++
          (`splice-wallet-daml` / Compile / damlBuild).value ++
          (`splice-wallet-payments-daml` / Compile / damlBuild).value ++
          (`splice-amulet-name-service-daml` / Compile / damlBuild).value ++
          (`splice-dso-governance-daml` / Compile / damlBuild).value ++
          (`splitwell-daml` / Compile / damlBuild).value ++
          (`splice-validator-lifecycle-daml` / Compile / damlBuild).value,
      damlTsCodegenDir := baseDirectory.value / "daml.js",
      damlTsCodegen := BuildCommon.damlTsCodegenTask.value,
      npmInstallDeps := baseDirectory.value / "package.json" +: damlTsCodegen.value,
      npmInstallOpenApiDeps :=
        Seq(
          (
            (`apps-validator` / Compile / compile).value,
            (`apps-validator` / Compile / baseDirectory).value,
            false,
          ),
          (
            (`apps-sv` / Compile / compile).value,
            (`apps-sv` / Compile / baseDirectory).value,
            false,
          ),
          (
            (`apps-wallet` / Compile / compile).value,
            (`apps-wallet` / Compile / baseDirectory).value,
            true,
          ),
          (
            (`apps-splitwell` / Compile / compile).value,
            (`apps-splitwell` / Compile / baseDirectory).value,
            false,
          ),
        ),
      npmInstall := BuildCommon.npmInstallTask.value,
      npmRootDir := baseDirectory.value / "../..",
      Compile / compile := {
        npmInstall.value
        (Compile / compile).value
      },
      bundle := {
        (Compile / compile).value
        val log = streams.value.log
        val cacheDir = streams.value.cacheDirectory
        val sourceFiles =
          (baseDirectory.value ** ("*.tsx" || "*.ts" || "*.js" || "*.json") --- baseDirectory.value / "lib" ** "*" --- baseDirectory.value / "node_modules" ** "*").get.toSet
        val cache =
          FileFunction.cached(cacheDir) { _ =>
            // openapi-generator-cli only generates .ts files so we need to
            // compile to get .d.ts and .js files. We cannot run this as part of
            // apps-common-frontend-openapi/compile because that does not yet run
            // npm install.
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "scan/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "sv/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "validator/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "validator/external-openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "validator/scan-proxy-openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "wallet/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "wallet/external-openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "splitwell/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "common-frontend-utils",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "common-frontend",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "common-test-utils",
              log,
            )
            (baseDirectory.value / "lib" ** "*").get.toSet
          }
        (baseDirectory.value / "lib", cache(sourceFiles))
      },
      // We could support npmLint and npmFix at the individual project level, but right now that doesn't seem very useful
      // so we just do it once for all workspaces here.
      npmLint := {
        val log = streams.value.log
        runCommand(
          Seq("npm", "run", "check", "--workspaces", "--if-present"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmFix := {
        val log = streams.value.log
        runCommand(
          Seq("npm", "run", "fix", "--workspaces", "--if-present"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      // TODO(#7579) -- like npmLint and npmFix above, we could/should run vitest per project.
      // In this case, we really want to do that asap to better parallelize the task in CI.
      npmTest := {
        val log = streams.value.log
        (Test / compile).value
        npmInstall.value
        for (workspace <- Seq("common-frontend-utils", "common-frontend", "common-test-utils"))
          BuildCommon.TS.runWorkspaceCommand(npmRootDir.value, "build", workspace, log)
        runCommand(
          Seq("npm", "run", "test:sbt", "--workspaces", "--if-present"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      cleanFiles += damlTsCodegenDir.value,
      cleanFiles += baseDirectory.value / "lib",
      cleanFiles += baseDirectory.value / "../../node_modules",
      Headers.TsHeaderSettings,
    )
}

/** Common settings to be used for frontends. Requires settings commonFrontendBundle and frontendWorkspace to be specified.
  */
lazy val sharedFrontendSettings: Seq[Setting[_]] = Seq(
  bundle := BuildCommon.bundleFrontend.value,
  cleanFiles += baseDirectory.value / "build",
  cleanFiles += baseDirectory.value / "node_modules",
) ++ Headers.TsHeaderSettings

lazy val `apps-wallet-frontend` = {
  project
    .in(file("apps/wallet/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "wallet-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-scan-frontend` = {
  project
    .in(file("apps/scan/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "scan-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-splitwell-frontend` = {
  project
    .in(file("apps/splitwell/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "splitwell-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-ans-frontend` = {
  project
    .in(file("apps/ans/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "ans-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-sv-frontend` = {
  project
    .in(file("apps/sv/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "sv-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-frontends` = {
  project
    .aggregate(
      `apps-common-frontend`,
      `apps-wallet-frontend`,
      `apps-ans-frontend`,
      `apps-sv-frontend`,
      `apps-scan-frontend`,
      `apps-splitwell-frontend`,
    )
    .settings(
      Headers.ApacheDAHeaderSettings
    )
}

lazy val `apps-wallet` =
  project
    .in(file("apps/wallet"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
      `splice-wallet-daml`,
      `splice-dso-governance-daml`,
    )
    .settings(
      BuildCommon.sharedAppSettings,
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "wallet-external-openapi",
        openApiSpec = "wallet-external.yaml",
        directory = "external-openapi-ts-client",
      ),
      BuildCommon.TS.openApiSettings(
        npmName = "wallet-openapi",
        openApiSpec = "wallet-internal.yaml",
      ),
      Compile / guardrailTasks :=
        List("external", "internal").flatMap { scope =>
          List(
            ScalaServer(
              new File(s"apps/wallet/src/main/openapi/wallet-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              modules = List("pekko-http-v1.0.0", "circe"),
              customExtraction = true,
            ),
            ScalaClient(
              new File(s"apps/wallet/src/main/openapi/wallet-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              modules = List("pekko-http-v1.0.0", "circe"),
            ),
          )
        },
    )

lazy val `apps-splitwell` =
  project
    .in(file("apps/splitwell"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
      `splitwell-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "splitwell-openapi",
        openApiSpec = "splitwell-internal.yaml",
      ),
      BuildCommon.sharedAppSettings,
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/splitwell/src/main/openapi/splitwell-internal.yaml"),
            pkg = "com.daml.network.http.v0",
            modules = List("pekko-http-v1.0.0", "circe"),
            customExtraction = true,
          ),
          ScalaClient(
            new File("apps/splitwell/src/main/openapi/splitwell-internal.yaml"),
            pkg = "com.daml.network.http.v0",
            modules = List("pekko-http-v1.0.0", "circe"),
          ),
        ),
      Compile / resourceGenerators += Def.task {
        val log = streams.value.log
        val splitwellOutput = (`splitwell-daml` / Compile / damlBuild).value
        val splitwellDar = ((`splitwell-daml` / Compile / damlBuild).value).head.toString
        val output1_0 =
          baseDirectory.value / "src" / "test" / "resources" / "splitwell-bundle-1.0.0.tar.gz"
        val output2_0 =
          baseDirectory.value / "src" / "test" / "resources" / "splitwell-bundle-2.0.0.tar.gz"
        val createBundle = baseDirectory.value / "../../scripts/create-bundle-for-app-mgr.sh"
        val cacheDir = streams.value.cacheDirectory
        val cache = FileFunction.cached(cacheDir) { _ =>
          runCommand(
            Seq(createBundle.toString, splitwellDar, "splitwell", "1.0.0", output1_0.toString),
            log,
            None,
            None,
          )
          runCommand(
            Seq(createBundle.toString, splitwellDar, "splitwell", "2.0.0", output2_0.toString),
            log,
            None,
            None,
          )
          Set(output1_0, output2_0)

        }
        cache((createBundle +: splitwellOutput).toSet).toSeq
      }.taskValue,
    )

lazy val pulumi =
  project
    .in(file("cluster/pulumi"))
    .disablePlugins(sbt.plugins.JvmPlugin, sbt.plugins.IvyPlugin)
    .settings(
      npmRootDir := baseDirectory.value,
      npmFix := {
        val log = streams.value.log
        npmInstall.value
        runCommand(
          Seq("npm", "run", "fix"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmLint := {
        val log = streams.value.log
        npmInstall.value
        runCommand(
          Seq("npm", "run", "check"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmInstall := {
        val s = streams.value
        val log = s.log
        val cacheDir = s.cacheDirectory
        val buildDir = (ThisBuild / baseDirectory).value
        val npmInstall = buildDir / "build-tools" / "npm-install.sh"
        val cache = FileFunction.cached(cacheDir / "npmInstall", FileInfo.hash) { _ =>
          runCommand(Seq(npmInstall.absolutePath), log, None, Some(npmRootDir.value))
          Set(npmRootDir.value / "node_modules")
        }
        cache(Set(npmRootDir.value / "package.json")).toSeq
      },
    )

lazy val `load-tester` =
  project
    .in(file("load-tester"))
    .settings(
      Headers.TsHeaderSettings,
      npmRootDir := baseDirectory.value,
      npmFix := {
        val log = streams.value.log
        npmInstall.value
        runCommand(
          Seq("npm", "run", "fix"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmLint := {
        val log = streams.value.log
        npmInstall.value
        runCommand(
          Seq("npm", "run", "check"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmInstall := {
        val s = streams.value
        val log = s.log
        val cacheDir = s.cacheDirectory
        val buildDir = (ThisBuild / baseDirectory).value
        val npmInstall = buildDir / "build-tools" / "npm-install.sh"
        val cache = FileFunction.cached(cacheDir / "npmInstall", FileInfo.hash) { _ =>
          runCommand(Seq(npmInstall.absolutePath), log, None, Some(npmRootDir.value))
          Set(npmRootDir.value / "node_modules")
        }
        cache(Set(npmRootDir.value / "package.json")).toSeq
      },
      npmBuild := {
        val log = streams.value.log
        npmLint.value
        runCommand(
          Seq("npm", "run", "build"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
    )

lazy val patchTemplate = taskKey[File]("patch an openapi codegen template")

lazy val `openapi-typescript-template` =
  project
    .in(file("openapi-templates"))
    .settings(
      patchTemplate := {
        val log = streams.value.log
        val template = baseDirectory.value / "typescript"
        val patch = baseDirectory.value / "typescript.patch"
        // ensure directory exists
        runCommand(Seq("mkdir", "-p", s"$template"), log)
        // copy the typescript template out to the directory
        runCommand(
          Seq(
            "openapi-generator-cli",
            "author",
            "template",
            "-g",
            "typescript",
            "-o",
            s"$template",
          ),
          log,
        )
        // apply a patch file
        runCommand(Seq("patch", "-p0", "-i", s"$patch"), log, optCwd = Some(baseDirectory.value))
        template
      },
      cleanFiles += baseDirectory.value / "typescript",
    )

def getCommittedDarFiles = {
  java.nio.file.Paths.get("daml").resolve("dars").toFile.listFiles("*.dar").toSeq
}

// Copied from Canton. Can probably be removed once we use Canton as a library.
def mergeStrategy(oldStrategy: String => MergeStrategy): String => MergeStrategy = {
  {
    case PathList("buf.yaml") => MergeStrategy.discard
    case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
    case "reflect.properties" => MergeStrategy.first
    case PathList("org", "checkerframework", _ @_*) => MergeStrategy.first
    case PathList("google", "protobuf", _*) => MergeStrategy.first
    case PathList("org", "apache", "logging", _*) => MergeStrategy.first
    case PathList("ch", "qos", "logback", _*) => MergeStrategy.first
    case PathList("com", "digitalasset", "canton", "config", "LocalNodeParametersConfig.class") =>
      MergeStrategy.first
    case PathList("META-INF", "okio.kotlin_module") => MergeStrategy.last
    case PathList(
          "META-INF",
          "org",
          "apache",
          "logging",
          "log4j",
          "core",
          "config",
          "plugins",
          "Log4j2Plugins.dat",
        ) =>
      MergeStrategy.first
    case (PathList("org", "apache", "pekko", "stream", "scaladsl", broadcasthub, _*))
        if broadcasthub.startsWith("BroadcastHub") =>
      MergeStrategy.first
    case "META-INF/versions/9/module-info.class" => MergeStrategy.discard
    case path if path.contains("module-info.class") => MergeStrategy.discard
    case PathList("org", "jline", _ @_*) => MergeStrategy.first
    // Dedup between ledger-api-java-proto (pulled in via Scala bindings)
    // and the copy of that inlined into bindings-java.
    case PathList("com", "daml", "ledger", "api", "v1" | "v2", _*) => MergeStrategy.first
    // Hack for not getting trouble with different versions of generated classes of common openapi
    case x @ PathList("com", "daml", "network", "http", "v0" | "commonAdmin", _*) =>
      MergeStrategy.first
    // this file comes in multiple flavors, from io.get-coursier:interface and from org.scala-lang.modules:scala-collection-compat. Since the content differs it is resolve this explicitly with this MergeStrategy.
    case path if path.endsWith("scala-collection-compat.properties") => MergeStrategy.first
    case x => oldStrategy(x)
  }
}

import sbtassembly.AssemblyPlugin.autoImport.assembly

/** Generate a release bundle. Simplified versions of Canton's release bundling (see Canton's code base / issue #147) */
lazy val bundleTask = {
  bundle := {
    val license = Seq("-c", "LICENSE")
    val log = streams.value.log
    val assemblyJar = assembly.value
    val examples = Seq("-c", "apps/app/src/pack")
    val testResources = Seq("-r", "apps/app/src/test/resources", "testResources")
    val transformConfig =
      Seq("-r", "scripts/transform-config.sc", "testResources/transform-config.sc")
    val dashboards = Seq(
      "-r",
      "cluster/pulumi/infra/grafana-dashboards",
      "grafana-dashboards",
      "-r",
      "network-health",
      "grafana-dashboards/docs",
    )
    val webUis =
      Seq(
        ((`apps-wallet-frontend` / bundle).value, "wallet"),
        ((`apps-ans-frontend` / bundle).value, "ans"),
        ((`apps-sv-frontend` / bundle).value, "sv"),
        ((`apps-scan-frontend` / bundle).value, "scan"),
        ((`apps-splitwell-frontend` / bundle).value, "splitwell"),
      )
    val dars =
      Seq(
        (`splice-amulet-daml` / Compile / damlBuild).value,
        (`splice-wallet-daml` / Compile / damlBuild).value,
        (`splitwell-daml` / Compile / damlBuild).value,
        (`splitwell-daml` / Compile / damlBuild).value,
        (`splice-dso-governance-daml` / Compile / damlBuild).value,
        (`splice-amulet-name-service-daml` / Compile / damlBuild).value,
        (`splice-wallet-payments-daml` / Compile / damlBuild).value,
        (`splice-app-manager-daml` / Compile / damlBuild).value,
        (`splice-validator-lifecycle-daml` / Compile / damlBuild).value,
        (`splice-util-daml` / Compile / damlBuild).value,
      )
    val docsArgs = Seq("-r", (`docs` / bundle).value._1.getPath, "docs")

    val committedDarFiles = getCommittedDarFiles
    val args: Seq[String] =
      license ++ examples ++ testResources ++ transformConfig ++ dashboards ++
        webUis.flatMap({ case ((source, _), name) =>
          Seq[String]("-r", source.toString, s"web-uis/$name")
        }) ++ dars.flatten.flatMap({ dar =>
          Seq[String]("-r", dar.toString, s"dars/${dar.getName}")
        }) ++ committedDarFiles.flatMap({ dar =>
          Seq[String]("-r", dar.toString, s"dars/${dar.getName}")
        }) ++ docsArgs

    val cacheDir = streams.value.cacheDirectory
    val main = (assembly / mainClass).value.get
    val cache = FileFunction.cached(cacheDir) { _ =>
      runCommand(
        Seq[String](
          "./create-bundle.sh",
          assemblyJar.toString,
          main,
        ) ++ args,
        log,
      )
      val buildFiles = ((assemblyJar.getParentFile.getParentFile / "release") ** "*").get.toSet
      buildFiles
    }
    val sourceFiles =
      webUis.foldLeft(Set.empty[File]) { case (acc, ((_, buildFiles), _)) =>
        acc union buildFiles
      } ++
        dars.foldLeft(Set.empty[File]) { case (a, b) => a ++ b } ++
        committedDarFiles.toSet +
        assemblyJar
    (assemblyJar, cache(sourceFiles))
  }
}

lazy val runShellcheck = taskKey[Unit]("Check shell scripts with shellcheck")
runShellcheck := {
  val log = streams.value.log
  runCommand(Seq("pre-commit", "run", "--all-files", "shellcheck"), log)
}

lazy val syncpackCheck = taskKey[Unit]("Check all apps' package.json dependency versions match")
syncpackCheck := {
  val log = streams.value.log
  runCommand(Seq("syncpack", "list-mismatches"), log, None, Some(baseDirectory.value / "apps"))
}

lazy val illegalDamlReferencesCheck =
  taskKey[Unit]("Check that there are no illegal references in Daml code")
illegalDamlReferencesCheck := {
  val log = streams.value.log
  runCommand(
    Seq("./scripts/rename.sh", "no_illegal_daml_references"),
    log,
    None,
    Some(baseDirectory.value),
  )
}

lazy val cleanCnDars = taskKey[Unit]("Remove all `.dar` files in `apps` and `canton-amulet`")
cleanCnDars := {
  val log = streams.value.log
  runCommand(Seq("find", "apps", "-name", "*.dar", "-delete"), log)
  // daml/dars contains the versions of all dars that we want to keep committed, so we don't delete them
  runCommand(Seq("find", "daml", "-name", "*.dar", "-not", "-path", "*daml/dars/*", "-delete"), log)
}

lazy val checkErrors = taskKey[Unit](
  "Check test log and canton logs for errors and fail if there is one (works best if Canton is no longer running)"
)
checkErrors := {
  import scala.sys.process._

  def ignorePatternsFilename(patternsName: String): String =
    s"project/ignore-patterns/$patternsName.ignore.txt"

  def checkLogs(logFileName: String, ignorePatterns: Seq[String]): Unit = {
    val ignorePatternsFilenames = ignorePatterns.map(ignorePatternsFilename)
    val cmd =
      Seq(
        ".circleci/canton-scripts/check-logs.sh",
        logFileName,
      ) ++ ignorePatternsFilenames
    if (cmd.! != 0) {
      sys.error(s"$logFileName contains problems.")
    }
  }

  def splitAndCheckCantonLogFile(
      logName: String,
      usesSimtime: Boolean,
  ): Unit = {
    val logFile = s"log/${logName}.clog"
    val logFileBefore = s"log/${logName}_before_shutdown.clog"
    val logFileAfter = s"log/${logName}_after_shutdown.clog"

    // Note that this will split the given file and then delete it, so it is idempotent.
    Seq(".circleci/canton-scripts/split-canton-logs.sh", logFile, logFileBefore, logFileAfter).!

    import better.files.File
    val logSpecificIgnores =
      if (File(ignorePatternsFilename(logName)).exists()) Seq(logName) else Seq.empty

    val simtimeIgnorePatterns = if (usesSimtime) Seq("canton_log_simtime_extra") else Seq.empty
    val beforeIgnorePatterns =
      Seq("canton_log") ++ simtimeIgnorePatterns ++ logSpecificIgnores
    val afterIgnorePatterns =
      beforeIgnorePatterns ++ Seq("canton_log_shutdown_extra") ++ logSpecificIgnores

    checkLogs(logFileBefore, beforeIgnorePatterns)
    checkLogs(logFileAfter, afterIgnorePatterns)
  }

  splitAndCheckCantonLogFile("canton", usesSimtime = false)
  splitAndCheckCantonLogFile("canton-simtime", usesSimtime = true)
  import better.files._
  val dir = File("log/")
  if (dir.exists())
    dir
      .glob("canton-standalone-*.clog")
      .map(_.nameWithoutExtension)
      .foreach { name =>
        splitAndCheckCantonLogFile(
          name,
          usesSimtime = false,
        )
      }

  checkLogs("log/canton_network_test.clog", Seq("canton_network_test_log"))
}

lazy val `apps-app` =
  project
    .in(file("apps/app"))
    .dependsOn(
      `apps-common`,
      `splice-wallet-payments-daml`,
      `splice-wallet-daml`,
      `apps-splitwell`,
      `apps-validator`,
      `apps-sv` % "compile->compile;test->test",
      `apps-scan`,
      `apps-wallet`,
      `canton-community-app` % "compile->compile;test->test",
      `canton-community-base`,
      `canton-community-integration-testing` % "test",
    )
    .settings(
      libraryDependencies += "org.scalatestplus" %% "selenium-4-12" % "3.2.17.0" % "test",
      libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "4.12.1" % "test",
      libraryDependencies += "eu.rekawek.toxiproxy" % "toxiproxy-java" % "2.1.4" % "test",
      libraryDependencies += google_cloud_storage,
      libraryDependencies += auth0,
      libraryDependencies += kubernetes_client,
      // Force SBT to use the right version of opentelemetry libs.
      dependencyOverrides ++= Seq(
        CantonDependencies.opentelemetry_api,
        CantonDependencies.opentelemetry_sdk,
        CantonDependencies.opentelemetry_sdk_autoconfigure,
        CantonDependencies.opentelemetry_sdk_autoconfigure,
        CantonDependencies.opentelemetry_prometheus,
        CantonDependencies.opentelemetry_zipkin,
        CantonDependencies.opentelemetry_instrumentation_grpc,
        CantonDependencies.opentelemetry_instrumentation_runtime_metrics,
      ),
      BuildCommon.sharedAppSettings,
      BuildCommon.cantonWarts,
      bundleTask,
      assembly / test := {}, // don't run tests during assembly
      // when building the fat jar, we need to properly merge our artefacts
      assembly / assemblyMergeStrategy := mergeStrategy((assembly / assemblyMergeStrategy).value),
      assembly / mainClass := Some("com.daml.network.SpliceApp"),
      assembly / assemblyJarName := "splice-node.jar",
      // include historic dars in the jar
      Compile / unmanagedResourceDirectories += { file(file(".").absolutePath) / "daml/dars" },
    )

// https://tanin.nanakorn.com/technical/2018/09/10/parallelise-tests-in-sbt-on-circle-ci.html
// also used by Canton team
lazy val printTests = taskKey[Unit](
  "write full class names of `apps-app` tests to separted files depending on whether the test is for Wall clock time vs Simulated time, Backend vs frontend, preflight; used for CI test splitting"
)
printTests := {
  import java.io._
  println("Appending full class names of tests.")

  def isTimeBasedTest(name: String): Boolean = name.contains("TimeBased")
  def isFrontEndTest(name: String): Boolean = name.contains("Frontend")
  def isNonDevNetTest(name: String): Boolean = name.contains("NonDevNet")
  def isPreflightIntegrationTest(name: String): Boolean = name.contains("PreflightIntegrationTest")

  def isCoreDeploymentPreflightIntegrationTest(name: String): Boolean = isPreflightIntegrationTest(
    name
  ) && !isValidator1DeploymentPreflightIntegrationTest(
    name
  ) && !isRunbookValidatorPreflightIntegrationTest(name) && !isRunbookSvPreflightIntegrationTest(
    name
  )
  def isValidator1DeploymentPreflightIntegrationTest(name: String): Boolean =
    isPreflightIntegrationTest(
      name
    ) && name.contains("Validator1PreflightIntegrationTest")
  def isRunbookSvPreflightIntegrationTest(name: String): Boolean =
    isPreflightIntegrationTest(name) && name.contains("RunbookSv")
  def isRunbookValidatorPreflightIntegrationTest(name: String): Boolean =
    isPreflightIntegrationTest(name) && name.contains("RunbookValidator")
  def isDecentralizedSynchronizerDeploymentPreflightIntegrationTest(name: String): Boolean =
    isPreflightIntegrationTest(
      name
    ) && name.contains("DecentralizedSynchronizerUpgradeCluster")
  def isSvOffboardPreflightIntegrationTest(name: String): Boolean =
    isPreflightIntegrationTest(
      name
    ) && name.contains("SvOffboard")
  def isSvReOnboardPreflightIntegrationTest(name: String): Boolean =
    isPreflightIntegrationTest(
      name
    ) && name.contains("SvReOnboard")
  def isDamlCiupgradeVote(name: String): Boolean = name contains "DamlCIUpgradeVote"
  def isAuth0CredentialsPreflightIntegrationTest(name: String): Boolean =
    isPreflightIntegrationTest(name) && name.contains("Auth0Credentials")

  def isGlobalSoftMigrationTest(name: String): Boolean =
    name contains "DecentralizedSynchronizerSoftDomainMigration"
  def isGlobalSoftTopologyMigrationTest(name: String): Boolean =
    name contains "SoftDomainMigrationTopologySetupIntegrationTest"
  def isAppManagerTest(name: String): Boolean = name contains "AppManager"
  def isDisasterRecoveryTest(name: String): Boolean = name contains "DisasterRecovery"
  def isAppUpgradeTest(name: String): Boolean = name contains "AppUpgrade"
  def isSvReonboardingTest(name: String): Boolean = name contains "SvReonboardingIntegration"

  val allTestNames =
    definedTests
      .all(ScopeFilter(inAggregates(root), inConfigurations(Test)))
      .value
      .flatten
      .map(_.name)

  // Order matters as each test is included in just one group, with the first match being used
  val testSplitRules = Seq(
    (
      "Daml ciupgrade vote",
      "test-daml-ciupgrade-vote.log",
      (t: String) => isDamlCiupgradeVote(t),
    ),
    (
      "Global domain upgrade cluster preflight",
      "test-full-class-names-global-domain-upgrade-preflight.log",
      (t: String) => isDecentralizedSynchronizerDeploymentPreflightIntegrationTest(t),
    ),
    (
      "SV offboard preflight",
      "test-full-class-names-offboard-sv-runbook-preflight.log",
      (t: String) => isSvOffboardPreflightIntegrationTest(t),
    ),
    (
      "SV reonboard preflight",
      "test-full-class-names-re-onboard-sv-runbook-preflight.log",
      (t: String) => isSvReOnboardPreflightIntegrationTest(t),
    ),
    (
      "Fetch UI credentials from Auth0 and store them in a k8s secret",
      "test-full-class-names-auth0-credentials-preflight.log",
      (t: String) => isAuth0CredentialsPreflightIntegrationTest(t),
    ),
    (
      "Preflight tests against core nodes",
      "test-full-class-names-core-preflight.log",
      (t: String) => isCoreDeploymentPreflightIntegrationTest(t) && !isNonDevNetTest(t),
    ),
    (
      "Preflight tests against validator1",
      "test-full-class-names-validator1-preflight.log",
      (t: String) => isValidator1DeploymentPreflightIntegrationTest(t) && !isNonDevNetTest(t),
    ),
    (
      "Non-DevNet Preflight tests against core nodes",
      "test-full-class-names-core-preflight-non-devnet.log",
      (t: String) => isCoreDeploymentPreflightIntegrationTest(t) && isNonDevNetTest(t),
    ),
    (
      "Preflight tests against runbook SV",
      "test-full-class-names-sv-preflight.log",
      (t: String) => isRunbookSvPreflightIntegrationTest(t) && !isNonDevNetTest(t),
    ),
    (
      "Non-DevNet Preflight tests against runbook SV",
      "test-full-class-names-sv-preflight-non-devnet.log",
      (t: String) => isRunbookSvPreflightIntegrationTest(t) && isNonDevNetTest(t),
    ),
    (
      "Preflight tests against runbook validator",
      "test-full-class-names-validator-preflight.log",
      (t: String) => isRunbookValidatorPreflightIntegrationTest(t) && !isNonDevNetTest(t),
    ),
    (
      "Non-DevNet Preflight tests against runbook validator",
      "test-full-class-names-validator-preflight-non-devnet.log",
      (t: String) => isRunbookValidatorPreflightIntegrationTest(t) && isNonDevNetTest(t),
    ),
    (
      "global domain soft migration test",
      "test-full-class-names-global-soft-migration.log",
      (t: String) => isGlobalSoftMigrationTest(t),
    ),
    (
      "global domain soft topology migration test",
      "test-full-class-names-global-soft-migration-topology.log",
      (t: String) => isGlobalSoftTopologyMigrationTest(t),
    ),
    (
      "disaster recovery tests",
      "test-full-class-names-disaster-recovery.log",
      (t: String) => !isTimeBasedTest(t) && isDisasterRecoveryTest(t),
    ),
    (
      "app upgrade tests",
      "test-full-class-names-app-upgrade.log",
      (t: String) => !isTimeBasedTest(t) && isAppUpgradeTest(t),
    ),
    (
      "sv reonboarding test",
      "test-full-class-names-sv-reonboarding.log",
      (t: String) => isSvReonboardingTest(t),
    ),
    (
      "tests with wall clock time",
      "test-full-class-names.log",
      (t: String) => !isTimeBasedTest(t) && !isFrontEndTest(t),
    ),
    (
      "tests with simulated time",
      "test-full-class-names-sim-time.log",
      (t: String) => isTimeBasedTest(t) && !isFrontEndTest(t),
    ),
    (
      "frontend tests with wall clock time",
      "test-full-class-names-frontend.log",
      (t: String) => !isTimeBasedTest(t) && isFrontEndTest(t) && !isAppManagerTest(t),
    ),
    (
      "frontend tests with app manager",
      "test-full-class-names-frontend-app-manager.log",
      (t: String) => !isTimeBasedTest(t) && isFrontEndTest(t) && isAppManagerTest(t),
    ),
    (
      "frontend tests with simulated time",
      "test-full-class-names-frontend-sim-time.log",
      (t: String) => isTimeBasedTest(t) && isFrontEndTest(t),
    ),
  )

  val rulesWithOpenFiles = testSplitRules.map { case (t, fileName, p) =>
    // append writes from the start of the file
    // if any content already exists and is longer than the content we're writing
    // it will produce a garbled mess between the two sources
    // therefore we first ensure that the file is deleted
    new File(fileName).delete()
    (t, new PrintWriter(new FileWriter(fileName, false)), p)
  }.zipWithIndex

  val (testCounts, unmatchedTestNames) =
    allTestNames.sorted.foldLeft((Map.empty[Int, Int], Vector.empty[String])) {
      case ((counts, unmatched), testName) =>
        rulesWithOpenFiles
          .collectFirst {
            case ((testSet, writer, predicate), countIx) if predicate(testName) =>
              (writer, countIx)
          }
          .map { case (writer, countIx) =>
            writer.println(testName)
            (counts.updated(countIx, counts.getOrElse(countIx, 0) + 1), unmatched)
          }
          .getOrElse {
            (counts, unmatched :+ testName)
          }
    }

  if (unmatchedTestNames.nonEmpty)
    sys.error(
      s"Could not find a matching CI category for tests ${unmatchedTestNames mkString ", "}"
    )

  rulesWithOpenFiles.foreach { case ((testSet, writer, _), countIx) =>
    val filteredLength = testCounts.getOrElse(countIx, 0)
    println(s"There are $filteredLength $testSet.")
    writer.close()
  }
}

Global / excludeLintKeys += `root` / wartremoverErrors
