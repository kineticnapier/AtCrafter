package io.github.kineticnapier.atcrafter.block;

import io.github.kineticnapier.atcrafter.AtCrafter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {
    public static final Block TERMINAL = register(
        "terminal",
        new Block(
            BlockBehaviour.Properties.of()
                .strength(3.0F, 6.0F)
                .sound(SoundType.METAL)
        )
    );

    private ModBlocks() {
    }

    private static Block register(String name, Block block) {
        ResourceLocation id = AtCrafter.id(name);

        Registry.register(BuiltInRegistries.BLOCK, id, block);
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties()));

        return block;
    }

    public static void initialize() {
        AtCrafter.LOGGER.info("Registered AtCrafter blocks");
    }
}
