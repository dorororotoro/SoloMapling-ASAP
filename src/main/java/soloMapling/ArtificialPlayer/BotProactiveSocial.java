package soloMapling.ArtificialPlayer;

import client.Character;
import net.server.world.Party;
import server.Trade;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lightweight, session-only social initiative. Runs on existing bot macro ticks:
 * no per-bot threads, new server messages, NPC spawns, or AI-model requests.
 *
 * This module only initiates genuine party invitations or existing merchant
 * trades. It does not claim to implement owned, persisted trading inventory.
 */
public final class BotProactiveSocial {
    private static final long PLAYER_PARTY_COOLDOWN_MS = 12 * 60_000L;
    private static final long PLAYER_TRADE_COOLDOWN_MS = 20 * 60_000L;
    private static final long SOLO_PARTY_WAIT_MS = 210_000L;
    private static final long TRADE_INVITE_WAIT_MS = 45_000L;
    private static final int MAX_DISTANCE_PX = 460;
    private static final double PARTY_CHANCE = 0.22;
    private static final double TRADE_CHANCE = 0.24;

    private static final Map<Integer, Long> NEXT_PARTY_ROLL = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> NEXT_TRADE_ROLL = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> PLAYER_PARTY_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> PLAYER_TRADE_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> SOLO_PARTY_EXPIRY = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> TRADE_PENDING_EXPIRY = new ConcurrentHashMap<>();

    private BotProactiveSocial() { }

    /** Call on each TrainingBot macro tick, even outside GRIND, to expire lonely parties. */
    public static void maybeOfferParty(BotSM bot, boolean eligibleToInvite) {
        Character chr = bot.getChr();
        if (chr == null || chr.getMap() == null) return;
        long now = System.currentTimeMillis();
        expireUnansweredParty(chr, now);

        if (!eligibleToInvite || !bot.getRunning() || bot.getState() != BotSM.BotState.RUNNING ||
                chr.getLevel() < 10 || chr.getParty() != null || chr.getTrade() != null ||
                !GCMovement.isMapObserved(chr.getMapId()) ||
                !ready(NEXT_PARTY_ROLL, chr.getId(), now, 180_000, 240_000)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= PARTY_CHANCE) return;

        Character player = pickPlayer(chr, false);
        if (player == null || !claim(PLAYER_PARTY_COOLDOWN, player.getId(), now, PLAYER_PARTY_COOLDOWN_MS)) {
            return;
        }
        try {
            if (BotPartyCommands.botInvitePlayer(chr, player)) {
                SOLO_PARTY_EXPIRY.put(chr.getId(), now + SOLO_PARTY_WAIT_MS);
            } else {
                cleanupEmptyParty(chr);
            }
        } catch (RuntimeException ignored) {
            // An invite race must not stop the scheduler or strand an empty party.
            cleanupEmptyParty(chr);
        }
    }

    /** Called only from SellingMerchantBot while it has a configured offer. */
    public static void maybeOfferMerchantTrade(BotSM bot, BotTradeSM.TradeMode mode) {
        Character chr = bot.getChr();
        if (chr == null || chr.getMap() == null) return;
        long now = System.currentTimeMillis();
        expireUnansweredTrade(chr, now);

        if (!bot.getRunning() || bot.getState() != BotSM.BotState.RUNNING ||
                mode != BotTradeSM.TradeMode.SELLING ||
                bot.getTradeInventory().getMainItemForSale() == null ||
                chr.getTrade() != null || !GCMovement.isMapObserved(chr.getMapId()) ||
                !ready(NEXT_TRADE_ROLL, chr.getId(), now, 150_000, 300_000)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= TRADE_CHANCE) return;

        Character player = pickPlayer(chr, true);
        if (player == null || !claim(PLAYER_TRADE_COOLDOWN, player.getId(), now, PLAYER_TRADE_COOLDOWN_MS)) {
            return;
        }
        try {
            // Existing Trade.inviteTrade checks coordinator ownership and recipient busy state.
            Trade.startTrade(chr);
            Trade.inviteTrade(chr, player);
            Trade trade = chr.getTrade();
            if (trade != null && trade.getPartner() != null && !trade.isFullTrade()) {
                TRADE_PENDING_EXPIRY.put(chr.getId(), now + TRADE_INVITE_WAIT_MS);
            } else if (trade != null && !trade.isFullTrade()) {
                Trade.declineTrade(chr);
            }
        } catch (RuntimeException ignored) {
            Trade trade = chr.getTrade();
            if (trade != null && !trade.isFullTrade()) {
                try { Trade.declineTrade(chr); } catch (RuntimeException alsoIgnored) { }
            }
        }
    }

    /** A pending invite must freeze the seller's rotating offer until reply or timeout. */
    public static boolean isAwaitingTradeResponse(Character chr) {
        return chr != null && TRADE_PENDING_EXPIRY.containsKey(chr.getId()) &&
                chr.getTrade() != null && !chr.getTrade().isFullTrade();
    }

    private static void expireUnansweredTrade(Character chr, long now) {
        Long expiry = TRADE_PENDING_EXPIRY.get(chr.getId());
        if (expiry == null) return;
        Trade trade = chr.getTrade();
        if (trade == null || trade.isFullTrade()) {
            TRADE_PENDING_EXPIRY.remove(chr.getId(), expiry);
        } else if (now >= expiry && TRADE_PENDING_EXPIRY.remove(chr.getId(), expiry)) {
            try { Trade.declineTrade(chr); } catch (RuntimeException ignored) { }
        }
    }

    private static void expireUnansweredParty(Character chr, long now) {
        Long expiry = SOLO_PARTY_EXPIRY.get(chr.getId());
        if (expiry == null) return;
        Party party = chr.getParty();
        if (party == null || party.getMembers().size() > 1) {
            SOLO_PARTY_EXPIRY.remove(chr.getId(), expiry);
        } else if (now >= expiry && SOLO_PARTY_EXPIRY.remove(chr.getId(), expiry)) {
            cleanupEmptyParty(chr);
        }
    }

    private static void cleanupEmptyParty(Character chr) {
        try {
            Party party = chr.getParty();
            if (party != null && party.getMembers().size() == 1 &&
                    party.getLeaderId() == chr.getId()) {
                BotPartyCommands.botLeaveParty(chr);
            }
        } catch (RuntimeException ignored) {
            // Safe to retry on a subsequent tick if the party still exists.
        }
    }

    private static Character pickPlayer(Character bot, boolean forTrade) {
        Point position = bot.getPosition();
        if (position == null) return null;
        List<Character> eligible = new ArrayList<>();
        for (Character player : bot.getMap().getCharacters()) {
            if (player == null || player == bot || BotHelpers.isBot(player) || player.isHidden() ||
                    player.getClient() == null || player.getMap() != bot.getMap()) {
                continue;
            }
            if (forTrade ? player.getTrade() != null : player.getParty() != null) continue;
            Point p = player.getPosition();
            if (p == null || Math.abs(p.x - position.x) > MAX_DISTANCE_PX ||
                    Math.abs(p.y - position.y) > MAX_DISTANCE_PX) continue;
            eligible.add(player);
        }
        return eligible.isEmpty() ? null : eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    }

    private static boolean ready(Map<Integer, Long> nextRoll, int botId, long now,
                                 int minIntervalMs, int jitterMs) {
        Long deadline = nextRoll.get(botId);
        if (deadline != null && now < deadline) return false;
        nextRoll.put(botId, now + minIntervalMs + ThreadLocalRandom.current().nextLong(jitterMs + 1L));
        return true;
    }

    private static boolean claim(Map<Integer, Long> cooldown, int playerId, long now, long durationMs) {
        final boolean[] accepted = {false};
        cooldown.compute(playerId, (key, until) -> {
            if (until == null || until <= now) {
                accepted[0] = true;
                return now + durationMs;
            }
            return until;
        });
        return accepted[0];
    }

    /** Drop only temporary state when a bot stops or changes type. */
    public static void onBotRemoved(Character chr) {
        if (chr == null) return;
        int id = chr.getId();
        NEXT_PARTY_ROLL.remove(id);
        NEXT_TRADE_ROLL.remove(id);
        SOLO_PARTY_EXPIRY.remove(id);
        if (TRADE_PENDING_EXPIRY.remove(id) != null) {
            Trade trade = chr.getTrade();
            if (trade != null && !trade.isFullTrade()) {
                try { Trade.declineTrade(chr); } catch (RuntimeException ignored) { }
            }
        }
    }
}
