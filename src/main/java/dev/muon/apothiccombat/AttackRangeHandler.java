package dev.muon.apothiccombat;

import net.bettercombat.api.client.AttackRangeExtensions;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.HashSet;
import java.util.Set;

public class AttackRangeHandler {

    public static void init() {
        AttackRangeExtensions.register(context -> {
            AttributeInstance reachAttribute = context.player().getAttribute(Attributes.ENTITY_INTERACTION_RANGE);

            if (reachAttribute == null) {
                return new AttackRangeExtensions.Modifier(0, AttackRangeExtensions.Operation.ADD);
            }

            double baseReach = reachAttribute.getBaseValue();

            ItemStack weapon = context.player().getMainHandItem();
            Set<AttributeModifier> weaponModifiers = new HashSet<>();
            ItemAttributeModifiers attributeModifiers = weapon.getAttributeModifiers();

            attributeModifiers.modifiers().stream()
                    .filter(entry -> entry.attribute().value() == Attributes.ENTITY_INTERACTION_RANGE)
                    .map(ItemAttributeModifiers.Entry::modifier)
                    .forEach(weaponModifiers::add);

            // Calculate total reach from all modifiers EXCEPT those directly on the weapon

            // This isn't perfect, but Apotheosis modifiers still get through this filter,

            // And it's important to prevent duplicate extension in the case where:
            // -- A mod adds entity interaction range to its weapon to cover the vanilla case
            // -- It also adds custom weapon attributes with increased range, for dedicated Better Combat support

            // In this case, it's important that we don't turn a 5 range weapon into a 7 range one.

            // This *might* break reach gems.
            double totalModifiedReach = baseReach;
            for (AttributeModifier modifier : reachAttribute.getModifiers()) {

                if (weaponModifiers.contains(modifier)) {
                    continue;
                }

                switch (modifier.operation()) {
                    case ADD_VALUE -> totalModifiedReach += modifier.amount();
                    case ADD_MULTIPLIED_BASE -> totalModifiedReach += baseReach * modifier.amount();
                    case ADD_MULTIPLIED_TOTAL -> totalModifiedReach *= (1.0 + modifier.amount());
                }

            }

            // Calculate the difference in reach and apply it to the weapon's attack range
            double reachDifference = totalModifiedReach - baseReach;
            return new AttackRangeExtensions.Modifier(reachDifference, AttackRangeExtensions.Operation.ADD);
        });
    }
}