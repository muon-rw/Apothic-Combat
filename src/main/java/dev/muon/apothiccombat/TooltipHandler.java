package dev.muon.apothiccombat;

import dev.shadowsoffire.apotheosis.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.affix.AttributeAffix;
import net.bettercombat.api.WeaponAttributes;
import net.bettercombat.logic.WeaponRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.ArrayList;
import java.util.List;


@EventBusSubscriber(modid = ApothicCombat.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class TooltipHandler {
    private static class ModifierCapturingEvent extends ItemAttributeModifierEvent {
        private final List<AttributeModifier> capturedModifiers = new ArrayList<>();

        public ModifierCapturingEvent(ItemStack stack) {
            super(stack, ItemAttributeModifiers.EMPTY);
        }

        @Override
        public boolean addModifier(Holder<Attribute> attribute, AttributeModifier modifier, EquipmentSlotGroup slot) {
            if (attribute.value() == Attributes.ENTITY_INTERACTION_RANGE.value()) {
                capturedModifiers.add(modifier);
            }
            return true;
        }

        public List<AttributeModifier> getCapturedModifiers() {
            return capturedModifiers;
        }
    }


    private static class ModifierTracker {
        double totalModifiedReach;
        final List<AttributeModifier> applicableModifiers = new ArrayList<>();

        ModifierTracker(double baseReach) {
            this.totalModifiedReach = baseReach;
        }

        void addModifier(AttributeModifier modifier, double baseReach) {
            if (modifier.amount() == 0) return;
            applicableModifiers.add(modifier);
            switch (modifier.operation()) {
                case ADD_VALUE -> totalModifiedReach += modifier.amount();
                case ADD_MULTIPLIED_BASE -> totalModifiedReach += baseReach * modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> totalModifiedReach *= (1.0 + modifier.amount());
            }
        }

        void subtractModifier(AttributeModifier modifier, double baseReach) {
            if (modifier.amount() == 0) return;
            applicableModifiers.remove(modifier);
            switch (modifier.operation()) {
                case ADD_VALUE -> totalModifiedReach -= modifier.amount();
                case ADD_MULTIPLIED_BASE -> totalModifiedReach -= baseReach * modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> totalModifiedReach /= (1.0 + modifier.amount());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        List<Component> tooltip = event.getToolTip();

        WeaponAttributes attributes = WeaponRegistry.getAttributes(stack);
        if (attributes == null) return;

        removeInteractionRangeTooltip(tooltip);
        addAttackRangeTooltip(event, stack, tooltip, attributes);
    }

    private static void removeInteractionRangeTooltip(List<Component> tooltip) {
        for (int i = 0; i < tooltip.size(); i++) {
            if (tooltip.get(i).toString().contains("attribute.name.player.entity_interaction_range")) {
                int endIndex = findModifierSectionEnd(tooltip, i, "entity_interaction_range");
                tooltip.subList(i, endIndex).clear();
                break;
            }
        }
    }

    private static int findModifierSectionEnd(List<Component> tooltip, int startIndex, String attributeKey) {
        int endIndex = startIndex + 1;
        while (endIndex < tooltip.size()) {
            String nextLine = tooltip.get(endIndex).toString();
            boolean isModifierLine = nextLine.startsWith("literal{ }") ||
                    nextLine.startsWith("literal{ ┇ }");

            if (!isModifierLine ||
                    (nextLine.startsWith("literal{ }") &&
                            !nextLine.contains(attributeKey) &&
                            !nextLine.contains("entity_interaction_range"))) {
                break;
            }
            endIndex++;
        }
        return endIndex;
    }

    private static void addAttackRangeTooltip(ItemTooltipEvent event, ItemStack stack, List<Component> tooltip, WeaponAttributes attributes) {
        for (int i = 0; i < tooltip.size(); i++) {
            if (tooltip.get(i).toString().contains("attribute.name.generic.attack_range")) {
                ModifierTracker tracker = new ModifierTracker(event.getEntity().getAttribute(Attributes.ENTITY_INTERACTION_RANGE).getBaseValue());
                double totalRange = calculateTotalRange(event, stack, attributes, tracker);

                if (event.getFlags().hasShiftDown()) {
                    addExpandedRangeTooltip(tooltip, i, totalRange, attributes, tracker);
                } else {
                    addCondensedRangeTooltip(tooltip, i, totalRange, tracker);
                }
                break;
            }
        }
    }


    private static double calculateTotalRange(ItemTooltipEvent event, ItemStack stack, WeaponAttributes attributes, ModifierTracker tracker) {
        AttributeInstance reachAttr = event.getEntity().getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (reachAttr == null) return attributes.attackRange();

        double baseReach = reachAttr.getBaseValue();

        // Add all entity modifiers
        reachAttr.getModifiers().forEach(modifier -> tracker.addModifier(modifier, baseReach));

        // Remove equipped item's modifiers to avoid double-counting
        deduplicateEquippedModifiers(event.getEntity().getMainHandItem(), tracker, baseReach);

        // Add viewed item's modifiers
        addViewedItemModifiers(stack, tracker, baseReach);

        return attributes.attackRange() + (tracker.totalModifiedReach - baseReach);
    }

    private static void deduplicateEquippedModifiers(ItemStack equippedStack, ModifierTracker tracker, double baseReach) {
        ModifierCapturingEvent equippedCaptureEvent = new ModifierCapturingEvent(equippedStack);
        AffixHelper.streamAffixes(equippedStack)
                .filter(inst -> inst.getAffix() instanceof AttributeAffix)
                .forEach(inst -> ((AttributeAffix) inst.getAffix()).addModifiers(inst, equippedCaptureEvent));

        equippedCaptureEvent.getCapturedModifiers()
                .forEach(modifier -> tracker.subtractModifier(modifier, baseReach));
    }

    private static void addViewedItemModifiers(ItemStack stack, ModifierTracker tracker, double baseReach) {
        ModifierCapturingEvent viewedCaptureEvent = new ModifierCapturingEvent(stack);
        AffixHelper.streamAffixes(stack)
                .filter(inst -> inst.getAffix() instanceof AttributeAffix)
                .forEach(inst -> ((AttributeAffix) inst.getAffix()).addModifiers(inst, viewedCaptureEvent));

        viewedCaptureEvent.getCapturedModifiers()
                .forEach(modifier -> tracker.addModifier(modifier, baseReach));
    }


    private static void addExpandedRangeTooltip(List<Component> tooltip, int index, double totalRange, WeaponAttributes attributes, ModifierTracker tracker) {
        List<Component> modifierLines = new ArrayList<>();
        ApothicCombat.LOGGER.info("=== Creating expanded tooltip ===");
        ApothicCombat.LOGGER.info("Current tooltip lines:");
        for (int i = 0; i < tooltip.size(); i++) {
            ApothicCombat.LOGGER.info("{}: {}", i, tooltip.get(i));
        }
        // Total line in gold
        tooltip.set(index, createTotalRangeComponent(totalRange, ChatFormatting.GOLD));

        // Base value in green
        modifierLines.add(createBaseWeaponRangeComponent(attributes.attackRange(), ChatFormatting.DARK_GREEN));

        // Add modifier lines
        for (AttributeModifier modifier : tracker.applicableModifiers) {
            modifierLines.add(createModifierComponents(modifier));
        }

        // Replace existing modifiers with new ones
        int nextSectionStart = findModifierSectionEnd(tooltip, index, "attack_range");
        if (index + 1 < nextSectionStart) {
            tooltip.subList(index + 1, nextSectionStart).clear();
        }

        tooltip.addAll(index + 1, modifierLines);
        ApothicCombat.LOGGER.info("=== Final tooltip lines ===");
        for (int i = 0; i < tooltip.size(); i++) {
            ApothicCombat.LOGGER.info("{}: {}", i, tooltip.get(i));
        }
    }


    private static void addCondensedRangeTooltip(List<Component> tooltip, int index, double totalRange, ModifierTracker tracker) {
        tooltip.set(index, createTotalRangeComponent(totalRange,
                !tracker.applicableModifiers.isEmpty() ? ChatFormatting.GOLD : ChatFormatting.DARK_GREEN));
    }

    private static Component createTotalRangeComponent(double range, ChatFormatting color) {
        return Component.literal(" ")
                .append(Component.translatable("attribute.modifier.equals.0",
                        ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT.format(range),
                        Component.translatable("attribute.name.generic.attack_range")
                )).withStyle(color);
    }

    private static Component createBaseWeaponRangeComponent(double value, ChatFormatting color) {
        return Component.literal(" \u2507 ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.translatable("attribute.modifier.equals.0",
                        ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT.format(value),
                        Component.translatable("attribute.name.generic.attack_range")
                ).withStyle(color));
    }

    private static Component createModifierComponents(AttributeModifier modifier) {
        boolean isPositive = modifier.amount() > 0;
        return Component.literal(" \u2507 ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.translatable("attribute.modifier." +
                                (isPositive ? "plus." : "take.") + modifier.operation().ordinal(),
                        ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT.format(Math.abs(modifier.amount())),
                        Component.translatable("attribute.name.player.entity_interaction_range")
                ).withStyle(isPositive ? ChatFormatting.BLUE : ChatFormatting.RED));
    }

}