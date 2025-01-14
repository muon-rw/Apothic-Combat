package dev.muon.apothiccombat;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ApothicCombat.MODID)
public class ApothicCombat {
    public static final String MODID = "apothiccombat";
    public static final Logger LOGGER = LogManager.getLogger();

    public ApothicCombat(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        ApothicCombat.LOGGER.info("Loading Apothic Combat");

        AttackRangeHandler.init();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    public static ResourceLocation loc(String id) {
        return ResourceLocation.fromNamespaceAndPath(ApothicCombat.MODID, id);
    }
}