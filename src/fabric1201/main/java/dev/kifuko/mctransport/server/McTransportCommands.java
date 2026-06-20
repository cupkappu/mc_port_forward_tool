package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.protocol.StreamMode;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Optional;
import java.util.UUID;

public final class McTransportCommands {

    private McTransportCommands() {
    }

    public static void register(RouteCommandService service) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("mctransport")
                    .requires(source -> source.hasPermissionLevel(4))
                    .then(setCommand(service))
                    .then(unsetCommand(service))
                    .then(listCommand(service)));
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> setCommand(
            RouteCommandService service) {
        return CommandManager.literal("set")
                .then(CommandManager.argument("playerName", StringArgumentType.word())
                        .then(CommandManager.argument("listenPort",
                                        IntegerArgumentType.integer(1, 65535))
                                .then(CommandManager.argument("targetHost",
                                                StringArgumentType.string())
                                        .then(CommandManager.argument("targetPort",
                                                        IntegerArgumentType.integer(1, 65535))
                                                .executes(ctx -> set(service,
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "playerName"),
                                                        IntegerArgumentType.getInteger(ctx, "listenPort"),
                                                        StringArgumentType.getString(ctx, "targetHost"),
                                                        IntegerArgumentType.getInteger(ctx, "targetPort")))
                                                .then(CommandManager.argument("mode",
                                                                StringArgumentType.word())
                                                        .executes(ctx -> set(service,
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "playerName"),
                                                                IntegerArgumentType.getInteger(ctx, "listenPort"),
                                                                StringArgumentType.getString(ctx, "targetHost"),
                                                                IntegerArgumentType.getInteger(ctx, "targetPort"),
                                                                StringArgumentType.getString(ctx, "mode"))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> unsetCommand(
            RouteCommandService service) {
        return CommandManager.literal("unset")
                .then(CommandManager.argument("playerName", StringArgumentType.word())
                        .then(CommandManager.argument("listenPort",
                                        IntegerArgumentType.integer(1, 65535))
                                .executes(ctx -> unset(service, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "playerName"),
                                        IntegerArgumentType.getInteger(ctx, "listenPort")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> listCommand(
            RouteCommandService service) {
        return CommandManager.literal("list")
                .executes(ctx -> list(service, ctx.getSource()));
    }

    private static int set(RouteCommandService service, ServerCommandSource source,
                           String playerName, int listenPort, String targetHost,
                           int targetPort) {
        return set(service, source, playerName, listenPort, targetHost, targetPort, "DIRECT");
    }

    private static int set(RouteCommandService service, ServerCommandSource source,
                           String playerName, int listenPort, String targetHost,
                           int targetPort, String modeStr) {
        ResolvedPlayer player = resolvePlayer(source.getServer(), playerName);
        StreamMode mode;
        try {
            mode = StreamMode.fromString(modeStr);
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal("Invalid mode: " + modeStr + ". Valid: DIRECT, KCP"));
            return 0;
        }
        String message = service.setRoute(player.uuid(), player.name(), listenPort,
                targetHost, targetPort, mode);
        source.sendFeedback(() -> Text.literal(message), true);
        return 1;
    }

    private static int unset(RouteCommandService service, ServerCommandSource source,
                             String playerName, int listenPort) {
        ResolvedPlayer player = resolvePlayer(source.getServer(), playerName);
        String message = service.unsetRoute(player.uuid(), player.name(), listenPort);
        source.sendFeedback(() -> Text.literal(message), true);
        return 1;
    }

    private static int list(RouteCommandService service, ServerCommandSource source) {
        for (String line : service.listRoutes()) {
            source.sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static ResolvedPlayer resolvePlayer(MinecraftServer server, String playerName) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerName);
        if (online != null) {
            return new ResolvedPlayer(online.getUuid(), online.getGameProfile().getName());
        }
        Optional<GameProfile> profile = server.getUserCache().findByName(playerName);
        if (profile.isPresent()) {
            return new ResolvedPlayer(profile.get().getId(), profile.get().getName());
        }
        throw new IllegalArgumentException("Player not found: " + playerName);
    }

    private record ResolvedPlayer(UUID uuid, String name) {
    }
}
