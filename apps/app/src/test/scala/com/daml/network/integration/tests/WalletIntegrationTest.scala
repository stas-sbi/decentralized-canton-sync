package com.daml.network.integration.tests

import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.splice.amulet as amuletCodegen
import com.daml.network.codegen.java.splice.types.Round
import com.daml.network.codegen.java.splice.wallet.payment as walletCodegen
import com.daml.network.http.v0.definitions.TapRequest
import com.daml.network.http.v0.wallet.WalletClient
import com.daml.network.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.util.{SpliceUtil, WalletTestUtil, JavaDecodeUtil as DecodeUtil}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.{DomainAlias, HasExecutionContext}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.slf4j.event.Level

import java.time.Duration
import java.util.UUID
import scala.concurrent.Future
import scala.util.Try

class WalletIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        config.copy(pekkoConfig =
          Some(
            // these settings are needed for the batching tests to pass,
            // since they require a lot of open / queued requests
            ConfigFactory.parseString(
              """
            |org.apache.pekko.http.host-connection-pool {
            |  max-connections = 20
            |  min-connections = 20
            |  max-open-requests = 128
            |}
            |""".stripMargin
            )
          )
        )
      )
      // TODO(#8300) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()
  }

  "A wallet" should {

    "tap stupid amount" in { implicit env =>
      import com.digitalasset.daml.lf.data.Numeric
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val round = sv1ScanBackend.getLatestOpenMiningRound(env.environment.clock.now)
      val price = round.contract.payload.amuletPrice
      val decimalScale = Numeric.Scale.assertFromInt(10)
      // We subtract one to allow some slack in back/forth conversions from CC to USD. Otherwise,
      // the command gets rejected by the participant and we test nothing.
      val maxDecimal = Numeric
        .subtract(Numeric.maxValue(decimalScale), Numeric.assertFromBigDecimal(decimalScale, 1))
        .value
      val maxUsd = Numeric
        .multiply(decimalScale, maxDecimal, Numeric.assertFromBigDecimal(decimalScale, price))
        .value
      // Integration test that the tap goes through
      aliceWalletClient.tap(maxUsd)
      val amulet = aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(amuletCodegen.Amulet.COMPANION)(aliceParty, _ => true)
        .loneElement
      // Unit test that expiry does the right thing
      SpliceUtil.amuletExpiresAt(amulet.data) shouldBe new Round(Long.MaxValue)
      // Test that the USD/CC conversions get us to the max Decimal value ignoring decimal points
      amulet.data.amount.initialAmount.setScale(0, java.math.RoundingMode.DOWN) shouldBe Numeric
        .maxValue(decimalScale)
        .setScale(0, java.math.RoundingMode.DOWN)
    }

    "tap deduplicates" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(50.0, Some("dedup-test"))
      assertThrowsAndLogsCommandFailures(
        aliceWalletClient.tap(50.0, Some("dedup-test")),
        _.errorMessage should include("DUPLICATE_COMMAND"),
      )
    }

    "allow two wallet app users to connect to one wallet backend and tap" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      aliceWalletClient.tap(walletAmuletToUsd(50.0))
      checkWallet(aliceUserParty, aliceWalletClient, Seq((50, 50)))

      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      charlieWalletClient.tap(walletAmuletToUsd(50.0))
      checkWallet(charlieUserParty, charlieWalletClient, Seq((50, 50)))
    }

    "skip empty batches in the treasury service" in { implicit env =>
      val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(49)
      // create and reject request such that...
      val (request, _) =
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          alice,
        )
      aliceWalletClient.rejectAppPaymentRequest(request)

      // The action is completed before the batch is skipped, so we need an eventuallyLogs here
      // to make sure we wait for the message.
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          def submitRequest() =
            try {
              // ... lookup on the payment request fails
              aliceWalletClient.acceptAppPaymentRequest(request)
            } catch {
              case _: CommandFailure =>
            }

          submitRequest()
        },
        entries => {
          forAtLeast(1, entries)(
            // .. and we see that the empty batch is skipped.
            _.message should include(
              "Amulet operation batch was empty after filtering"
            )
          )
        },
      )
    }

    "concurrent amulet-operations" should {
      "be batched" in { implicit env =>
        val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(50)
        val requestIds =
          (1 to 3).map(_ =>
            createSelfPaymentRequest(
              aliceValidatorBackend.participantClientWithAdminToken,
              aliceWalletClient.config.ledgerApiUser,
              alice,
            )
          )
        val offsetBefore =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.end()
        // sending three commands in short succession to the idle wallet should lead to two transactions being executed
        // tx 1: first command that arrived is immediately executed
        // tx 2: other commands that arrived after the first command was started are executed in one batch
        requestIds.foreach { case (requestId, _) =>
          Future(aliceWalletClient.acceptAppPaymentRequest(requestId)).discard
        }

        // Wait until 2 transactions have been received
        val txs =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.transactions
            .treesJava(Set(alice), completeAfter = 2, beginOffset = offsetBefore)
        val createdAmuletsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(amuletCodegen.Amulet.COMPANION)(_).size)
        val createdLockedAmuletsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(amuletCodegen.LockedAmulet.COMPANION)(_).size)

        // in rare cases all 3 commands get batched in one transaction,
        // so we only check if the 3 commands are included in the 2 transactions

        // create change
        createdAmuletsInTx.sum shouldBe 3
        // lock amulet
        createdLockedAmuletsInTx.sum shouldBe 3

        (createdAmuletsInTx zip createdLockedAmuletsInTx).foreach { case (cc, clc) =>
          cc shouldBe clc
        }
      }

      "be batched up to `batchSize` concurrent amulet-operations" in { implicit env =>
        val batchSize = aliceValidatorBackend.config.treasury.batchSize
        val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(1000)

        val requests =
          (0 to batchSize + 1).map(_ =>
            createSelfPaymentRequest(
              aliceValidatorBackend.participantClientWithAdminToken,
              aliceWalletClient.config.ledgerApiUser,
              alice,
            )
          )

        eventually() {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(
              walletCodegen.AppPaymentRequest.COMPANION
            )(alice) should have size (batchSize.toLong + 2)
        }

        val offsetBefore =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.end()

        requests.foreach { case (requestId, _) =>
          Future(aliceWalletClient.acceptAppPaymentRequest(requestId)).discard
        }

        // 3 txs; usually (but not always):
        // tx 1: initial transfer
        // tx 2: batchSize subsequent batched transfers
        // tx 3: single transfer that was not picked due to the batch size limit
        val txs =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.transactions
            .treesJava(Set(alice), completeAfter = 3, beginOffset = offsetBefore)
        val createdAmuletsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(amuletCodegen.Amulet.COMPANION)(_).size)
        val createdLockedAmuletsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(amuletCodegen.LockedAmulet.COMPANION)(_).size)

        // all operations are contained in at most 3 transactions
        createdAmuletsInTx.sum shouldBe (batchSize.toLong + 2)
        createdLockedAmuletsInTx.sum shouldBe (batchSize.toLong + 2)

        // one transaction is "maxed out"
        createdAmuletsInTx.exists(_ == batchSize.toLong)
        createdLockedAmuletsInTx.exists(_ == batchSize.toLong)

        (createdAmuletsInTx zip createdLockedAmuletsInTx).foreach { case (cc, clc) =>
          cc shouldBe clc
        }
      }

      "filter stale actions from batches, and complete the rest" in { implicit env =>
        val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

        aliceWalletClient.tap(1)
        // creating payment request
        val (requestId, _) =
          createSelfPaymentRequest(
            aliceValidatorBackend.participantClientWithAdminToken,
            aliceWalletClient.config.ledgerApiUser,
            alice,
          )
        // Reject it so that we have a reference to an already archived app payment request
        aliceWalletClient.rejectAppPaymentRequest(requestId)

        loggerFactory.suppressErrors({

          val tapsBefore = Range(0, 3).map(_ => Future(Try(aliceWalletClient.tap(10))))

          // fails because we don't have a payment request - so removed from batch & error is reported back
          val failedAcceptF = Future(Try(aliceWalletClient.acceptAppPaymentRequest(requestId)))

          val tapsAfter = Range(0, 3).map(_ => Future(Try(aliceWalletClient.tap(10))))

          // Wait for all futures to complete
          val successfulTaps = (tapsBefore ++ tapsAfter).map(_.futureValue).count(_.isSuccess)
          if (failedAcceptF.futureValue.isSuccess)
            fail("The AcceptTransferOffer action unexpectedly succeeded")

          successfulTaps should be(
            (tapsBefore ++ tapsAfter).length
          ) withClue "All taps should succeed"

          checkBalance(
            aliceWalletClient,
            None,
            (
              walletUsdToAmulet(1 + successfulTaps * 10 - 1),
              walletUsdToAmulet(1 + successfulTaps * 10),
            ),
            exactly(0),
            (0, smallAmount),
          )
        })
      }
    }

    "reject HS256 JWTs with invalid signatures" in { implicit env =>
      implicit val sys = env.actorSystem
      registerHttpConnectionPoolsCleanup(env)

      import com.auth0.jwt.JWT
      import com.auth0.jwt.algorithms.Algorithm

      val invalidSignatureToken = JWT
        .create()
        .withAudience(aliceValidatorBackend.config.auth.audience)
        .withSubject(aliceWalletClient.config.ledgerApiUser)
        .sign(Algorithm.HMAC256("wrong-secret"))

      implicit val httpClient: HttpRequest => Future[HttpResponse] =
        request => Http().singleRequest(request = request)
      val walletClient = WalletClient(aliceWalletClient.httpClientConfig.url.toString())

      def tokenHeader(token: String) = List(Authorization(OAuth2BearerToken(token)))

      val responseForInvalidSignature =
        walletClient
          .tap(TapRequest(amount = "10.0"), headers = tokenHeader(invalidSignatureToken))
          .leftOrFail("should fail with unauthorized")
          .futureValue
          .value
      responseForInvalidSignature.status should be(StatusCodes.Unauthorized)
    }

    "reject HS256 JWTs with invalid audiences" in { implicit env =>
      implicit val sys = env.actorSystem
      registerHttpConnectionPoolsCleanup(env)

      import com.auth0.jwt.JWT

      val invalidAudienceToken = JWT
        .create()
        .withAudience("wrong-audience")
        .withSubject(aliceWalletClient.config.ledgerApiUser)
        .sign(AuthUtil.testSignatureAlgorithm)

      implicit val httpClient: HttpRequest => Future[HttpResponse] =
        request => Http().singleRequest(request = request)
      val walletClient = WalletClient(aliceWalletClient.httpClientConfig.url.toString())

      def tokenHeader(token: String) = List(Authorization(OAuth2BearerToken(token)))

      val responseForInvalidSignature =
        walletClient
          .tap(TapRequest(amount = "10.0"), headers = tokenHeader(invalidAudienceToken))
          .leftOrFail("should fail with unauthorized")
          .futureValue
          .value

      responseForInvalidSignature.status should be(StatusCodes.Unauthorized)
    }

    "support featured app rewards" in { implicit env =>
      val splitwellProvider = onboardWalletUser(splitwellWalletClient, splitwellValidatorBackend)
      splitwellWalletClient.userStatus().hasFeaturedAppRight shouldBe false

      clue("Canceling a featured app right before getting it, nothing bad should happen")(
        splitwellWalletClient.cancelFeaturedAppRight()
      )

      clue("grant a featured app right to splitwell provider") {
        eventually() {
          noException should be thrownBy grantFeaturedAppRight(splitwellWalletClient)
        }
      }

      clue("splitwell provider is featured") {
        eventually() {
          inside(sv1ScanBackend.listFeaturedAppRights()) { case Seq(r) =>
            r.payload.provider shouldBe splitwellProvider.toProtoPrimitive
          }
          splitwellWalletClient.userStatus().hasFeaturedAppRight shouldBe true
        }
      }

      actAndCheck(
        "splitwell cancels its own featured app right",
        splitwellWalletClient.cancelFeaturedAppRight(),
      )(
        "splitwell provider is no longer featured",
        { _ =>
          sv1ScanBackend.listFeaturedAppRights() shouldBe empty
          splitwellWalletClient.userStatus().hasFeaturedAppRight shouldBe false
        },
      )

      actAndCheck(
        "Splitwell provider grants itself a featured app right",
        // We need to retry as the command might failed due to inactive cached AmuletRules contract
        // The failed command submission will triggers a cache invalidation
        retryCommandSubmission(splitwellWalletClient.selfGrantFeaturedAppRight()),
      )(
        "splitwell provider is featured",
        { featuredAppRight =>
          {
            inside(sv1ScanBackend.listFeaturedAppRights()) { case Seq(r) =>
              r.contractId shouldBe featuredAppRight
            }
            splitwellWalletClient.userStatus().hasFeaturedAppRight shouldBe true
          }
        },
      )
    }

    "transfer AppPaymentRequest and DeliveryOffer to global domain" in { implicit env =>
      val splitwellDomainId = aliceValidatorBackend.participantClientWithAdminToken.domains.id_of(
        DomainAlias.tryCreate("splitwell")
      )
      val decentralizedSynchronizerId =
        aliceValidatorBackend.participantClientWithAdminToken.domains.id_of(
          DomainAlias.tryCreate("global")
        )
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(50)
      val (_, requestId) = actAndCheck(
        "Create payment request on private domain",
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceParty,
          domainId = Some(splitwellDomainId),
        ),
      )(
        "request and delivery offer get transferred to global domain",
        { case (request, _) =>
          val domains =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .lookup_contract_domain(aliceParty, Set(request.contractId))
          domains shouldBe Map[String, DomainId](
            request.contractId -> decentralizedSynchronizerId
          )
          request
        },
      )
      val request = eventually() {
        inside(aliceWalletClient.listAppPaymentRequests()) { case Seq(req) =>
          req
        }
      }
      request.contractId shouldBe requestId
      actAndCheck(
        "Accept payment request",
        aliceWalletClient.acceptAppPaymentRequest(request.contractId),
      )(
        "wait for the accepted payment to appear",
        _ =>
          inside(aliceWalletClient.listAcceptedAppPayments()) { case Seq(accepted) =>
            accepted
          },
      )
    }

    "automation and offboarding work even if the recipient user does not exist" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceUser = aliceWalletClient.config.ledgerApiUser
      val aliceValidatorUser = aliceValidatorBackend.config.ledgerApiUser
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(50)

      val (offerCid, _) =
        actAndCheck(
          "Alice creates transfer",
          aliceWalletClient.createTransferOffer(
            bobParty,
            10,
            "transfer 10 amulets to Bob",
            CantonTimestamp.now().plus(Duration.ofMinutes(1)),
            UUID.randomUUID.toString,
          ),
        )(
          "Bob sees transfer offer",
          _ => bobWalletClient.listTransferOffers() should have length 1,
        )

      // We simulate things here that can happen during a hard domain migration or disaster recovery.
      clue("Alice's validator shuts down") {
        aliceValidatorBackend.stop()
      }
      actAndCheck(
        "Alice's user disappears",
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users.delete(aliceUser),
      )(
        "Alice's user is gone",
        _ =>
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users
            .list()
            .users
            .find(_.id == aliceUser) shouldBe None,
      )
      actAndCheck(
        "Alice's validator loses rights over Alice's party",
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users.rights
          .revoke(aliceValidatorUser, Set(aliceParty), Set()),
      )(
        "Alice's validator has no rights over Alice's party",
        _ => {
          val rights =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users.rights
              .list(aliceValidatorUser)
          rights.actAs should not(contain((aliceParty)))
          rights.readAs should not(contain((aliceParty)))
        },
      )
      clue("Alice's validator starts back up") {
        aliceValidatorBackend.startSync()
      }

      actAndCheck(
        "Bob accepts transfer offer",
        bobWalletClient.acceptTransferOffer(offerCid),
      )(
        "Bob sees updated balance",
        _ => {
          bobWalletClient.listTransferOffers() should have length 0
          bobWalletClient.balance().unlockedQty should beAround(10)
        },
      )

      clue("Alice is listed as a user") {
        aliceValidatorBackend.listUsers() should contain(aliceUser)
      }
      actAndCheck(
        "We offboard Alice",
        aliceValidatorBackend.offboardUser(aliceUser),
      )(
        "Alice is offboarded",
        _ => aliceValidatorBackend.listUsers() should not(contain((aliceUser))),
      )
      clue("Alice's validator has no rights over Alice's party") {
        eventually() {
          val rights =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users.rights
              .list(aliceValidatorUser)
          rights.actAs should not(contain((aliceParty)))
          rights.readAs should not(contain((aliceParty)))
        }
      }

      clue("Alice can reonboard and transfer some more amulets") {
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        p2pTransfer(aliceWalletClient, bobWalletClient, bobParty, 10)
      }
    }
  }
}
