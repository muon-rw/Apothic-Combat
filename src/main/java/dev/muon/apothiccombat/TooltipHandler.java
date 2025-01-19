package dev.muon.apothiccombat;

import net.bettercombat.api.WeaponAttributes;
import net.bettercombat.logic.WeaponRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.ArrayList;
import java.util.List;


@EventBusSubscriber(modid = ApothicCombat.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class TooltipHandler {

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
        Player player = event.getEntity();
        if (!(player instanceof LocalPlayer)) return;

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

            if (nextLine.contains("attribute.name.generic.attack_speed") ||
                    nextLine.contains("attribute.name.generic.attack_damage")) {
                break;
            }

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
        if (attributes == null) {
            return;
        }

        int insertIndex = -1;
        int lastAttributeIndex = -1;
        for (int i = 0; i < tooltip.size(); i++) {
            String line = tooltip.get(i).toString();
            if (line.contains("attribute.name.generic.attack_speed") ||
                    line.contains("attribute.name.generic.attack_damage")) {
                lastAttributeIndex = i;
                insertIndex = findModifierSectionEnd(tooltip, i, "attack_speed");
            }
        }

        if (insertIndex != -1) {
            for (int i = lastAttributeIndex + 1; i < insertIndex; i++) {
                String line = tooltip.get(i).toString();
                if (line.contains("attribute.name.generic.")) {
                    insertIndex = i;
                    break;
                }
            }
        } else {
            insertIndex = tooltip.size();
        }

        Player player = event.getEntity();
        if (!(player instanceof LocalPlayer)) return;
        AttributeInstance entityRange = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (entityRange == null) {
            ApothicCombat.LOGGER.warn("Player {} has no entity interaction range attribute!", event.getEntity().getDisplayName());
            return;
        }

        ModifierTracker tracker = new ModifierTracker(entityRange.getBaseValue());
        double totalRange = calculateTotalRange(event, stack, attributes, tracker);
        double baseWeaponRange = attributes.rangeBonus() + Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue();
        boolean hasModifications = Math.abs(totalRange - baseWeaponRange) > 0.001;

        if (event.getFlags().hasShiftDown() && hasModifications) {
            addExpandedRangeTooltip(tooltip, insertIndex, totalRange, attributes, tracker);
        } else {
            tooltip.add(insertIndex, createTotalRangeComponent(totalRange,
                    hasModifications ? ChatFormatting.GOLD : ChatFormatting.DARK_GREEN));
        }
    }

    private static double calculateTotalRange(ItemTooltipEvent event, ItemStack stack, WeaponAttributes attributes, ModifierTracker tracker) {
        Player player = event.getEntity();
        if (player == null) return attributes.rangeBonus() + Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue();
        AttributeInstance reachAttr = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (reachAttr == null) return attributes.rangeBonus() + Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue();

        double baseReach = reachAttr.getBaseValue();
        ItemStack equippedStack = player.getMainHandItem();
        boolean isViewingEquipped = stack == equippedStack;

        for (AttributeModifier modifier : reachAttr.getModifiers()) {
            tracker.addModifier(modifier, baseReach);
        }

        if (!isViewingEquipped) {
            deduplicateHeldItemModifiers(equippedStack, tracker, baseReach);
            addViewedItemModifiers(stack, tracker, baseReach);
        }

        return attributes.rangeBonus() + Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue() + (tracker.totalModifiedReach - baseReach);
    }

    private static void addViewedItemModifiers(ItemStack stack, ModifierTracker tracker, double baseReach) {
        stack.getAttributeModifiers().modifiers().forEach(entry -> {
            if (entry.attribute().value() == Attributes.ENTITY_INTERACTION_RANGE.value()) {
                tracker.addModifier(entry.modifier(), baseReach);
            }
        });
    }

    private static void deduplicateHeldItemModifiers(ItemStack equippedStack, ModifierTracker tracker, double baseReach) {
        equippedStack.getAttributeModifiers().forEach(EquipmentSlotGroup.MAINHAND, (attribute, modifier) -> {
            if (attribute.value() == Attributes.ENTITY_INTERACTION_RANGE.value()) {
                tracker.subtractModifier(modifier, baseReach);
            }
        });
    }

    private static void addExpandedRangeTooltip(List<Component> tooltip, int index, double totalRange, WeaponAttributes attributes, ModifierTracker tracker) {
        List<Component> modifierLines = new ArrayList<>();
        tooltip.set(index, createTotalRangeComponent(totalRange, ChatFormatting.GOLD));

        modifierLines.add(createBaseWeaponRangeComponent(attributes.rangeBonus() + Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue(), ChatFormatting.DARK_GREEN));

        for (AttributeModifier modifier : tracker.applicableModifiers) {
            modifierLines.add(createModifierComponents(modifier));
        }
        int nextSectionStart = findModifierSectionEnd(tooltip, index, "attack_range");
        if (index + 1 < nextSectionStart) {
            tooltip.subList(index + 1, nextSectionStart).clear();
        }

        tooltip.addAll(index + 1, modifierLines);
    }

    private static void addCondensedRangeTooltip(List<Component> tooltip, int index, double totalRange, boolean hasModifications, ModifierTracker tracker) {
        tooltip.set(index, createTotalRangeComponent(totalRange,
                hasModifications ? ChatFormatting.GOLD : ChatFormatting.DARK_GREEN));
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