/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.utilities.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.function.Predicate

class PlayerStorageTest :
    StringSpec({
        "pure reads do not populate storage and round-trip saves non-empty player modesets" {
            val dir = Files.createTempDirectory("ocm-player-storage-test")
            val bsonPath = dir.resolve("players.bson")
            val worldId = UUID.randomUUID()
            val unknownPlayerId = UUID.randomUUID()
            val savedPlayerId = UUID.randomUUID()

            PlayerStorage.setOnlinePlayerCheck(Predicate { true })
            try {
                PlayerStorage.initialise(null, bsonPath)

                PlayerStorage.getPlayerData(unknownPlayerId).modesetByWorld.isEmpty().shouldBeTrue()

                val data = PlayerData()
                data.setModesetForWorld(worldId, "old")
                PlayerStorage.setPlayerData(savedPlayerId, data)
                PlayerStorage.instantSave()
                Files.exists(bsonPath).shouldBeTrue()

                PlayerStorage.initialise(null, bsonPath)
                PlayerStorage.getPlayerData(savedPlayerId).getModesetForWorld(worldId) shouldBe "old"
                PlayerStorage.getPlayerData(unknownPlayerId).getModesetForWorld(worldId) shouldBe null

                val cleared = PlayerStorage.getPlayerData(savedPlayerId)
                cleared.clearModesetForWorld(worldId)
                PlayerStorage.setPlayerData(savedPlayerId, cleared)
                PlayerStorage.instantSave()

                PlayerStorage.initialise(null, bsonPath)
                PlayerStorage.getPlayerData(savedPlayerId).getModesetForWorld(worldId) shouldBe null
            } finally {
                PlayerStorage.resetOnlinePlayerCheck()
                PlayerStorage.instantSave()
                Files.deleteIfExists(bsonPath)
                Files.deleteIfExists(dir)
            }
        }

        "unlogged bot UUIDs stay in transient memory and never schedule saves or write to bson" {
            val dir = Files.createTempDirectory("ocm-bot-storage-test")
            val bsonPath = dir.resolve("players.bson")
            val worldId = UUID.randomUUID()
            val botId = UUID.randomUUID()
            val onlinePlayerId = UUID.randomUUID()
            var scheduledCount = 0

            PlayerStorage.setOnlinePlayerCheck(Predicate { it == onlinePlayerId })
            try {
                PlayerStorage.initialise(null, bsonPath)

                val botData = PlayerStorage.getPlayerData(botId)
                botData.setModesetForWorld(worldId, "old")
                PlayerStorage.setPlayerData(botId, botData)
                PlayerStorage.scheduleSaveForTest {
                    scheduledCount++
                    Runnable {}
                }

                scheduledCount shouldBe 0
                PlayerStorage.getPlayerData(botId).getModesetForWorld(worldId) shouldBe "old"

                val playerData = PlayerStorage.getPlayerData(onlinePlayerId)
                playerData.setModesetForWorld(worldId, "new")
                PlayerStorage.setPlayerData(onlinePlayerId, playerData)

                PlayerStorage.instantSave()
                PlayerStorage.initialise(null, bsonPath)

                PlayerStorage.getPlayerData(botId).getModesetForWorld(worldId) shouldBe null
                PlayerStorage.getPlayerData(onlinePlayerId).getModesetForWorld(worldId) shouldBe "new"
            } finally {
                PlayerStorage.resetOnlinePlayerCheck()
                PlayerStorage.instantSave()
                Files.deleteIfExists(bsonPath)
                Files.deleteIfExists(dir)
            }
        }

        "scheduleSave coalesces repeated requests instead of scheduling duplicate async tasks" {
            val dir = Files.createTempDirectory("ocm-coalesce-save-test")
            val bsonPath = dir.resolve("players.bson")
            val worldId = UUID.randomUUID()
            val playerId = UUID.randomUUID()
            var scheduledCount = 0
            var cancelledCount = 0

            PlayerStorage.setOnlinePlayerCheck(Predicate { true })
            try {
                PlayerStorage.initialise(null, bsonPath)
                val data = PlayerData()
                data.setModesetForWorld(worldId, "old")
                PlayerStorage.setPlayerData(playerId, data)

                repeat(30) {
                    PlayerStorage.scheduleSaveForTest {
                        scheduledCount++
                        Runnable { cancelledCount++ }
                    }
                }
                scheduledCount shouldBe 1
                cancelledCount shouldBe 0

                PlayerStorage.instantSave()
                cancelledCount shouldBe 1
            } finally {
                PlayerStorage.resetOnlinePlayerCheck()
                Files.deleteIfExists(bsonPath)
                Files.deleteIfExists(dir)
            }
        }

        "corrupted bson file fails gracefully without crashing initialisation" {
            val dir = Files.createTempDirectory("ocm-corrupt-storage-test")
            val bsonPath = dir.resolve("players.bson")
            PlayerStorage.setOnlinePlayerCheck(Predicate { true })
            try {
                Files.write(bsonPath, byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F, 0x01))
                PlayerStorage.initialise(null, bsonPath)
                PlayerStorage.getPlayerData(UUID.randomUUID()).modesetByWorld.isEmpty().shouldBeTrue()
            } finally {
                PlayerStorage.resetOnlinePlayerCheck()
                Files.deleteIfExists(bsonPath)
                Files.deleteIfExists(dir)
            }
        }
    })
