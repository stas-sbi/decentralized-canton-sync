package com.daml.network.store

import com.daml.ledger.javaapi.data.{CreatedEvent, DamlRecord, ExercisedEvent, Int64, Value}
import com.digitalasset.daml.lf.data.Bytes
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.store.TreeUpdateWithMigrationId
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.util.MonadUtil
import com.google.rpc.status.Status
import com.google.rpc.status.Status.toJavaProto

import java.time.Instant
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class UpdateHistoryTest extends UpdateHistoryTestBase {

  import UpdateHistoryTestBase.*

  protected def updates(
      store: UpdateHistory,
      migrationId: Long = migration1,
  ): Future[Seq[LedgerClient.GetTreeUpdatesResponse]] = {
    store
      .getUpdates(None, PageLimit.tryCreate(1000))
      .map(_.filter(_.migrationId == migrationId).map(_.update))
  }

  "UpdateHistory" should {

    "ingestion" should {

      "handle single create" in {
        val store = mkStore()
        for {
          _ <- initStore(store)
          _ <- create(domain1, cid1, offset1, party1, store)
          updates <- updates(store)
        } yield checkUpdates(
          updates,
          Seq(
            ExpectedCreate(cid1, domain1)
          ),
        )
      }

      "handle transaction with non-standard data" in {
        val store = mkStore()
        for {
          _ <- initStore(store)
          expectedTree <- domain1.ingest(offset => {
            val party1 = mkPartyId("someParty").toProtoPrimitive
            val party2 = mkPartyId("someParty").toProtoPrimitive
            val contractId = nextCid()
            val id1 = new com.daml.ledger.javaapi.data.Identifier(
              "somePackageId1",
              "someModuleName1",
              "someEntityName1",
            )
            val someValue = new DamlRecord(
              new DamlRecord.Field("a", new Int64(42))
            )
            val effectiveAt = CantonTimestamp.Epoch.toInstant
            val recordTime = effectiveAt.plusSeconds(60)
            // Unlike transactions generated by various helper functions, this transaction has
            // a created and exercised event where every field is set to some (arbitrary) non-empty value,
            // including fields that are not normally not used in Splice, such as interfaces or contract keys.
            // This is to make sure serialization doesn't crash on such unexpected optional fields.
            mkTx(
              offset = offset,
              events = Seq(
                new CreatedEvent(
                  /*witnessParties*/ Seq(party1).asJava,
                  /*eventId*/ "someEventId",
                  /*templateId*/ id1,
                  /*packageName*/ "somePackageName",
                  /*contractId*/ contractId,
                  /*arguments*/ someValue,
                  /*createdEventBlob*/ Bytes.assertFromString("00abcd").toByteString,
                  /*interfaceViews*/ new java.util.HashMap(Map(id1 -> someValue).asJava),
                  /*failedInterfaceViews*/ new java.util.HashMap(
                    Map(id1 -> toJavaProto(Status.of(1, "some message", Seq.empty))).asJava
                  ),
                  /*contractKey*/ Some[Value](someValue).toJava,
                  /*signatories*/ Seq(party1, party2).asJava,
                  /*observers*/ Seq(party1, party2).asJava,
                  /*createdAt*/ effectiveAt,
                ),
                new ExercisedEvent(
                  /*witnessParties*/ Seq(party1).asJava,
                  /*eventId*/ "otherEventId",
                  /*templateId*/ id1,
                  /*packageName*/ dummyPackageName,
                  /*interfaceId*/ Some(id1).toJava,
                  /*contractId*/ contractId,
                  /*choice*/ "someChoice",
                  /*choiceArgument*/ someValue,
                  /*actingParties*/ List(party1).asJava,
                  /*consuming*/ false,
                  /*childEventIds*/ List("someEventId").asJava,
                  /*exerciseResult*/ someValue,
                ),
              ),
              domainId = domain1,
              effectiveAt = effectiveAt,
              recordTime = recordTime,
              workflowId = "SomeWorkflowId",
              commandId = "SomeCommandId",
            )
          })(store)
          updates <- updates(store)
        } yield {
          val expectedUpdates = Seq(
            GetTreeUpdatesResponse(
              TransactionTreeUpdate(expectedTree),
              domain1,
            )
          )
          updates.map(withoutLostData(_)) should contain theSameElementsInOrderAs expectedUpdates
            .map(
              withoutLostData(_)
            )
        }
      }

      "handle reassignments" in {
        implicit val store = mkStore()
        val c = appRewardCoupon(1, party1, contractId = cid1)
        for {
          _ <- initStore(store)
          _ <- domain1.create(
            c,
            txEffectiveAt = CantonTimestamp.Epoch.plusMillis(1).toInstant,
            recordTime = CantonTimestamp.Epoch.plusMillis(1).toInstant,
          )
          _ <- domain1.unassign(
            c -> domain2,
            reassignmentId1,
            1,
            CantonTimestamp.Epoch.plusMillis(2),
          )
          _ <- domain2.assign(c -> domain1, reassignmentId1, 1, CantonTimestamp.Epoch.plusMillis(3))
          _ <- domain2.exercise(
            c,
            None,
            "Archive",
            com.daml.ledger.javaapi.data.Unit.getInstance(),
            com.daml.ledger.javaapi.data.Unit.getInstance(),
            txEffectiveAt = CantonTimestamp.Epoch.plusMillis(4).toInstant,
            recordTime = CantonTimestamp.Epoch.plusMillis(4).toInstant,
          )
          updates <- updates(store)
        } yield checkUpdates(
          updates,
          Seq(
            ExpectedCreate(cid1, domain1),
            ExpectedUnassign(cid1, domain1, domain2),
            ExpectedAssign(cid1, domain1, domain2),
            ExpectedExercise(cid1, domain2, "Archive"),
          ),
        )
      }

      // Note: we do not really want to support multiple UpdateHistory instances ingesting
      // data for the same party from the same participant. We still want the UpdateHistory
      // to behave correctly if this happens by accident, however.
      "handle many stores concurrently ingesting the same stream" in {
        // 10 stores, all ingesting the same stream
        val stores = (1 to 10).toList.map(_ => mkStore())

        // One update stream with 10 updates
        val updateStreamElements = (1 to 10).toList.map(i =>
          i -> TransactionTreeUpdate(
            mkCreateTx(
              validOffset(i),
              Seq(appRewardCoupon(1, party1, contractId = validContractId(i))),
              defaultEffectiveAt.plusMillis(i.toLong),
              Seq(party1),
              domain1,
              "workflowId",
              recordTime = defaultEffectiveAt.plusMillis(i.toLong),
            )
          )
        )

        // Retry once on failure
        def retryOnceAfterAShortDelay[T](f: => Future[T]): Future[T] = {
          f.recoverWith { case _: Throwable =>
            Future(Threading.sleep(100)).flatMap(_ => f)
          }
        }

        for {
          // Initialize all stores in parallel
          _ <- Future.traverse(stores)(s => initStore(s))
          // Process one update at a time
          _ <- withoutRepeatedIngestionWarning(
            MonadUtil.sequentialTraverse(updateStreamElements) { case (i, update) =>
              logger.info(s"Processing update $i")
              // Ingest the same update on all stores in parallel
              Future.traverse(stores)(s =>
                // The first concurrent update is expected to fail on all but one store
                // with a uniqueness violation error (assuming the updates are really concurrent).
                // At the latest on the next retry, all stores should succeed ingesting the update
                // by figuring out that the given offset was already ingested.
                // In practice, the ingestion service would crash and restart after a "short delay".
                retryOnceAfterAShortDelay(
                  s.ingestionSink
                    .ingestUpdate(
                      domain1,
                      update,
                    )
                )
              )
            },
            maxCount = 199, // 10 stores x 10 updates x up to 2 retries per update
          )
          // Query all stores in parallel
          updatesList <- Future.traverse(stores)(s => updates(s))
        } yield {
          // All stores should return all 10 updates
          updatesList.foreach(updates =>
            checkUpdates(
              updates,
              updateStreamElements.map(u => ExpectedCreate(validContractId(u._1), domain1)),
            )
          )

          succeed
        }
      }

      "two stores: different parties" in {
        val store1 = mkStore(party1, migration1, participant1)
        val store2 = mkStore(party2, migration1, participant1)

        for {
          _ <- initStore(store1)
          _ <- initStore(store2)
          _ <- create(domain1, cid1, offset1, party1, store1, time(1))
          _ <- create(domain1, cid2, offset2, party2, store2, time(2))
          updates1 <- updates(store1)
          updates2 <- updates(store2)
        } yield {
          checkUpdates(
            updates1,
            Seq(
              ExpectedCreate(cid1, domain1)
            ),
          )
          checkUpdates(
            updates2,
            Seq(
              ExpectedCreate(cid2, domain1)
            ),
          )
        }
      }

      "two stores: different participant" in {
        val store1 = mkStore(party1, migration1, participant1)
        val store2 = mkStore(party1, migration1, participant2)

        for {
          _ <- initStore(store1)
          _ <- initStore(store2)
          // Note: same offset (offsets are participant-specific)
          _ <- create(domain1, cid1, offset1, party1, store1, time(1))
          _ <- create(domain1, cid2, offset1, party1, store2, time(2))
          updates1 <- updates(store1)
          updates2 <- updates(store2)
        } yield {
          checkUpdates(
            updates1,
            Seq(
              ExpectedCreate(cid1, domain1)
            ),
          )
          checkUpdates(
            updates2,
            Seq(
              ExpectedCreate(cid2, domain1)
            ),
          )
        }
      }

      "two stores: different store_name, same participant" in {
        val store1 = mkStore(party1, migration1, participant1, "store2")
        val store2 = mkStore(party1, migration1, participant1, "store3")

        for {
          _ <- initStore(store1)
          _ <- initStore(store2)
          // Note: same offset (offsets are participant-specific)
          _ <- create(domain1, cid1, offset1, party1, store1, time(1))
          _ <- create(domain1, cid2, offset1, party1, store2, time(2))
          updates1 <- updates(store1)
          updates2 <- updates(store2)
        } yield {
          checkUpdates(
            updates1,
            Seq(
              ExpectedCreate(cid1, domain1)
            ),
          )
          checkUpdates(
            updates2,
            Seq(
              ExpectedCreate(cid2, domain1)
            ),
          )
        }
      }

      "two stores: different migration indices" in {
        val store1 = mkStore(party1, migration1, participant1)
        val store2 = mkStore(party1, migration2, participant1)

        for {
          _ <- initStore(store1)
          _ <- initStore(store2)
          // Note: same offset (offsets are not preserved across hard domain migrations)
          _ <- create(domain1, cid1, offset1, party1, store1, time(1))
          _ <- create(domain1, cid2, offset1, party1, store2, time(2))
          updates1 <- updates(store1, migration1)
          updates2 <- updates(store2, migration2)
        } yield {
          checkUpdates(
            updates1,
            Seq(
              ExpectedCreate(cid1, domain1)
            ),
          )
          checkUpdates(
            updates2,
            Seq(
              ExpectedCreate(cid2, domain1)
            ),
          )
        }
      }

      "one store: different domains" in {
        val store1 = mkStore(party1, migration1, participant1)

        for {
          _ <- initStore(store1)
          // Note: the two contracts can share a record time (record times are not unique across domains)
          _ <- create(domain1, cid1, offset1, party1, store1, time(1))
          _ <- create(domain2, cid2, offset2, party1, store1, time(2))
          updates1 <- updates(store1)
        } yield {
          checkUpdates(
            updates1,
            Seq(
              ExpectedCreate(cid1, domain1),
              ExpectedCreate(cid2, domain2),
            ),
          )
        }
      }

      "pagination works" in {
        val storeMigrationId1 = mkStore(party1, migration1, participant1)
        val storeMigrationId2 = mkStore(party1, migration2, participant1)

        val updates = (1 to 10).toList.map(i =>
          TransactionTreeUpdate(
            mkCreateTx(
              validOffset(i),
              Seq(appRewardCoupon(1, party1, contractId = validContractId(i))),
              defaultEffectiveAt.plusMillis(i.toLong),
              Seq(party1),
              domain1,
              s"workflowId#$i",
              recordTime = defaultEffectiveAt.plusMillis(i.toLong),
            )
          )
        )

        def allHistoryPaginated(
            store: UpdateHistory,
            after: Option[(Long, Instant)],
            acc: Seq[TransactionTreeUpdate],
        ): Seq[TransactionTreeUpdate] = {
          val result =
            store
              .getUpdates(
                after.map { case (migrationId, recordTime) =>
                  (migrationId, CantonTimestamp.assertFromInstant(recordTime))
                },
                PageLimit.tryCreate(1),
              )
              .futureValue
          result.lastOption match {
            case None => acc // done
            case Some(TreeUpdateWithMigrationId(last, migrationId)) =>
              last.update match {
                case tree: TransactionTreeUpdate =>
                  allHistoryPaginated(
                    store,
                    Some((migrationId, tree.tree.getRecordTime)),
                    acc :+ tree,
                  )
                case ReassignmentUpdate(transfer) =>
                  allHistoryPaginated(
                    store,
                    Some((migrationId, transfer.recordTime.toInstant)),
                    acc,
                  )
              }
          }
        }

        for {
          _ <- initStore(storeMigrationId1)
          _ <- withoutRepeatedIngestionWarning(
            MonadUtil.sequentialTraverse(updates) { update =>
              storeMigrationId1.ingestionSink
                .ingestUpdate(
                  domain1,
                  update,
                )
            },
            maxCount = updates.size,
          )
          _ <- initStore(storeMigrationId2)
          // insert the same transactions but in migration id 2,
          _ <- withoutRepeatedIngestionWarning(
            MonadUtil.sequentialTraverse(updates) { update =>
              storeMigrationId2.ingestionSink
                .ingestUpdate(
                  domain1,
                  update,
                )
            },
            maxCount = updates.size,
          )
          all <- storeMigrationId1.getUpdates(None, PageLimit.tryCreate(1000))
          all2 <- storeMigrationId2.getUpdates(None, PageLimit.tryCreate(1000))
        } yield {
          // It doesn't matter through which store we query since the migration id only matters for ingestion
          all shouldBe all2
          allHistoryPaginated(storeMigrationId1, None, Seq.empty) should be(
            all.map(_.update.update)
          )
          val expected = ((1 to 10).map(i =>
            (1L, CantonTimestamp.assertFromInstant(defaultEffectiveAt.plusMillis(i.toLong)))
          ) ++
            (1 to 10).map(i =>
              (2L, CantonTimestamp.assertFromInstant(defaultEffectiveAt.plusMillis(i.toLong)))
            ))
          all.map { case TreeUpdateWithMigrationId(u, migrationId) =>
            (migrationId, u.update.recordTime)
          } shouldBe expected
        }
      }

    }
  }
}
