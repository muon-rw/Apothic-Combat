package dev.muon.apothiccombat;

import net.bettercombat.api.client.AttackRangeExtensions;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

public class AttackRangeHandler {
    public static void init() {
        AttackRangeExtensions.register(context -> {
            ItemStack weapon = context.player().getMainHandItem();
            ItemAttributeModifiers attributeModifiers = weapon.getAttributeModifiers();

            double totalNegation = 0;
            for (ItemAttributeModifiers.Entry entry : attributeModifiers.modifiers()) {
                if (context.player().getAttribute(Attributes.ENTITY_INTERACTION_RANGE) == null) break;
                if (entry.attribute().value() == Attributes.ENTITY_INTERACTION_RANGE) {
                    AttributeModifier modifier = entry.modifier();
                    switch (modifier.operation()) {
                        case ADD_VALUE -> totalNegation -= modifier.amount();
                        case ADD_MULTIPLIED_BASE -> totalNegation -= Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue() * modifier.amount();
                        case ADD_MULTIPLIED_TOTAL -> totalNegation -= context.player().getAttribute(Attributes.ENTITY_INTERACTION_RANGE).getValue() * modifier.amount();
                    }
                }
            }

            return new AttackRangeExtensions.Modifier(totalNegation, AttackRangeExtensions.Operation.ADD);
        });
    }
}