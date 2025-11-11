package com.ksandr707.carpet_shadow_legacy;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ksandr707.carpet_shadow_legacy.component.ShadowNBTData;
import com.ksandr707.carpet_shadow_legacy.interfaces.ShadowItem;
import com.ksandr707.carpet_shadow_legacy.utility.RandomString;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class CarpetShadowLegacy implements CarpetExtension, ModInitializer {
    public static final Map<String, Pair<ItemStack, List<Pair<Inventory, Integer>>>> shadowMap = new HashMap<>();
    public static final Logger LOGGER = LogManager.getLogger("Carpet-Shadow-Legacy");
    public static RandomString shadow_id_generator = new RandomString(CarpetShadowLegacySettings.shadowItemIdSize);

    @Override
    public void onGameStarted() {
        CarpetShadowLegacy.LOGGER.info("Carpet Shadow Legacy Loaded!");
        CarpetServer.settingsManager.parseSettingsClass(CarpetShadowLegacySettings.class);
        shadow_id_generator = new RandomString(CarpetShadowLegacySettings.shadowItemIdSize);
    }

    @Override
    public void onInitialize() {
        CarpetServer.manageExtension(new CarpetShadowLegacy());
        new ShadowNBTData();
            ServerWorldEvents.LOAD.register((server, world) -> {
                for (PlayerEntity player : world.getPlayers()) {
                    Globals.updateInventory(player.getInventory());
                    Globals.updateInventory(player.getEnderChestInventory());
                }
            });
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                PlayerEntity player = handler.player;
                Globals.updateInventory(player.getInventory());
                Globals.updateInventory(player.getEnderChestInventory());
            });
            ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
                Globals.removeInventory(handler.player.getInventory());
                Globals.removeInventory(handler.player.getEnderChestInventory());
            });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("shadow")
                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                .then(CommandManager.literal("stabilize")
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        ServerPlayerEntity player = source.getPlayer();
                        if (player == null) {
                            source.sendError(Text.literal("Player-only command."));
                            return 0;
                        }

                        ItemStack mainHand = player.getMainHandStack();
                        if (!mainHand.isEmpty() && !hasShadowedCounterpart(player, mainHand)) {
                            source.sendError(Text.literal("This item does not have a shadowed item in the player's inventory."));
                            return 0;
                        }

                        int stabilized = stabilizeShadowInventory(player);
                        if (stabilized == 0) {
                            source.sendError(Text.literal("No matching stacks found in inventory."));
                        } else {
                            source.sendFeedback(() -> Text.literal("Stabilized " + stabilized + " stack(s)."), false);
                        }
                        return stabilized;
                    })
                )
            )
        );
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        InputStream langFile = getClass().getClassLoader().getResourceAsStream("assets/carpet-shadow-legacy/lang/%s.json".formatted(lang));
        if (langFile == null) {
            return Collections.emptyMap();
        }
        String jsonData;
        try {
            jsonData = IOUtils.toString(langFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
        Gson gson = new GsonBuilder().setLenient().create();
        return gson.fromJson(jsonData, new TypeToken<Map<String, String>>() {}.getType());
    }

    private static int stabilizeShadowInventory(ServerPlayerEntity player) {
        ItemStack mainHand = player.getMainHandStack();
        if (mainHand.isEmpty()) {
            return 0;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack baseStack = mainHand.copy();
        ((ShadowItem)(Object) baseStack).removeShadow();

        String shadowId = ((ShadowItem)(Object) mainHand).isItShadowItem()
                ? ((ShadowItem)(Object) mainHand).getShadowId()
                : null;

        List<Integer> matchingSlots = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            ItemStack compare = stack.copy();
            ((ShadowItem)(Object) compare).removeShadow();

            if (ItemStack.areItemsAndComponentsEqual(baseStack, compare)) {
                matchingSlots.add(i);
                ShadowItem shadowStack = (ShadowItem)(Object) stack;
                if (shadowId == null && shadowStack.isItShadowItem()) {
                    shadowId = shadowStack.getShadowId();
                }
            }
        }

        if (matchingSlots.isEmpty()) {
            return 0;
        }

        if (shadowId == null || shadowId.isEmpty()) {
            shadowId = CarpetShadowLegacy.shadow_id_generator.nextString();
        }

        for (int slotIndex : matchingSlots) {
            ItemStack stack = inventory.getStack(slotIndex);
            ShadowItem shadowStack = (ShadowItem)(Object) stack;
            shadowStack.setShadowId(shadowId);
            Globals.getByIdOrAdd(shadowId, stack);
        }
        Globals.updateInventory(inventory);
        return matchingSlots.size();
    }

    private static boolean hasShadowedCounterpart(ServerPlayerEntity player, ItemStack mainHand) {
        if (mainHand.isEmpty()) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        int matches = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i) == mainHand) {
                matches++;
                if (matches > 1) {
                    return true;
                }
            }
        }

        Inventory enderChest = player.getEnderChestInventory();
        for (int i = 0; i < enderChest.size(); i++) {
            if (enderChest.getStack(i) == mainHand) {
                return true;
            }
        }
        return false;
    }
}