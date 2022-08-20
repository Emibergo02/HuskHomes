package net.william278.huskhomes.command;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.player.OnlineUser;
import net.william278.huskhomes.player.UserData;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.util.Permission;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class RtpCommand extends CommandBase implements ConsoleExecutable {

    protected RtpCommand(@NotNull HuskHomes implementor) {
        super("rtp", Permission.COMMAND_RTP, implementor);
    }

    @Override
    public void onExecute(@NotNull OnlineUser onlineUser, @NotNull String[] args) {
        OnlineUser target = onlineUser;
        if (args.length == 1) {
            Optional<OnlineUser> foundUser = plugin.findPlayer(args[0]);
            if (foundUser.isEmpty()) {
                plugin.getLocales().getLocale("error_player_not_found", args[0])
                        .ifPresent(onlineUser::sendMessage);
                return;
            }
            if (!onlineUser.hasPermission(Permission.COMMAND_RTP_OTHER.node)) {
                plugin.getLocales().getLocale("error_no_permission").ifPresent(onlineUser::sendMessage);
                return;
            }
            target = foundUser.get();
        } else if (args.length > 1) {
            plugin.getLocales().getLocale("error_invalid_syntax", "/rtp [player]")
                    .ifPresent(onlineUser::sendMessage);
            return;
        }
        final Position userPosition = target.getPosition().join();
        if (plugin.getSettings().rtpRestrictedWorlds.stream()
                .anyMatch(worldName -> worldName.equals(userPosition.world.name))) {
            plugin.getLocales().getLocale("error_rtp_restricted_world")
                    .ifPresent(onlineUser::sendMessage);
            return;
        }

        // Perform economy check if necessary
        if (!plugin.validateEconomyCheck(onlineUser, Settings.EconomyAction.RANDOM_TELEPORT)) {
            return;
        }

        final OnlineUser userToTeleport = target;
        CompletableFuture.runAsync(() -> {
            // Check the user is not still on /rtp cooldown
            final Optional<UserData> userData = plugin.getDatabase().getUserData(onlineUser.uuid).join();
            if (userData.isEmpty()) {
                return;
            }
            final Instant currentTime = Instant.now();
            if (!userToTeleport.uuid.equals(onlineUser.uuid) &&
                !currentTime.isAfter(userData.get().rtpCooldown()) && !onlineUser.hasPermission(Permission.BYPASS_RTP_COOLDOWN.node)) {
                plugin.getLocales().getLocale("error_rtp_cooldown",
                                Long.toString(currentTime.until(userData.get().rtpCooldown(), ChronoUnit.MINUTES)))
                        .ifPresent(onlineUser::sendMessage);
                return;
            }

            // Get a random position and teleport
            plugin.getRtpEngine().getRandomPosition(userPosition)
                    .thenAccept(position -> {
                        if (position.isEmpty()) {
                            plugin.getLocales().getLocale("error_rtp_randomization_timeout")
                                    .ifPresent(onlineUser::sendMessage);
                            return;
                        }
                        plugin.getTeleportManager().timedTeleport(userToTeleport, position.get(), Settings.EconomyAction.RANDOM_TELEPORT)
                                .thenAccept(result -> {
                                    if (userToTeleport.uuid.equals(onlineUser.uuid) &&
                                        result.successful && !onlineUser.hasPermission(Permission.BYPASS_RTP_COOLDOWN.node)) {
                                        plugin.getDatabase().updateUserData(new UserData(onlineUser,
                                                userData.get().homeSlots(), userData.get().ignoringTeleports(),
                                                Instant.now().plus(plugin.getSettings().rtpCooldownLength, ChronoUnit.MINUTES)));
                                    }
                                    plugin.getTeleportManager()
                                            .finishTeleport(userToTeleport, result, Settings.EconomyAction.RANDOM_TELEPORT);
                                }).join();
                    }).join();
        });
    }

    @Override
    public void onConsoleExecute(@NotNull String[] args) {
        //todo
    }
}