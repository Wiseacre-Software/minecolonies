package com.minecolonies.api.tileentities;

import com.minecolonies.api.blocks.AbstractBlockMinecoloniesRack;
import com.minecolonies.api.blocks.types.RackType;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.inventory.container.ContainerRack;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.Direction;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static com.minecolonies.api.util.constant.Constants.*;
import static com.minecolonies.api.util.constant.NbtTagConstants.*;

/**
 * Tile entity for the warehouse shelves.
 */
public class TileEntityRack extends AbstractTileEntityRack
{
    /**
     * The content of the chest.
     */
    private final Map<ItemStorage, Integer> content = new HashMap<>();

    /**
     * Size multiplier of the inventory. 0 = default value. 1 = 1*9 additional slots, and so on.
     */
    private int size = 0;

    /**
     * The combined inv wrapper for double racks.
     */
    private CombinedInvWrapper combinedHandler;

    public TileEntityRack(final TileEntityType<? extends TileEntityRack> type)
    {
        super(type);
    }

    public TileEntityRack()
    {
        super(MinecoloniesTileEntities.RACK);
    }

    @Override
    public void setInWarehouse(final Boolean isInWarehouse)
    {
        this.inWarehouse = isInWarehouse;
    }

    @Override
    public boolean freeStacks()
    {
        return content.isEmpty();
    }

    @Override
    public int getFreeSlots()
    {
        int freeSlots = inventory.getSlots();
        for (final Map.Entry<ItemStorage, Integer> entry : content.entrySet())
        {
            final double slotsNeeded = (double) entry.getValue() / entry.getKey().getItemStack().getMaxStackSize();
            freeSlots -= (int) Math.ceil(slotsNeeded);
        }
        return freeSlots;
    }

    @Override
    public boolean hasItemStack(final ItemStack stack, final boolean ignoreDamageValue)
    {
        final ItemStorage checkItem = new ItemStorage(stack, ignoreDamageValue);

        return content.getOrDefault(checkItem, 0) >= stack.getCount();
    }

    @Override
    public int getCount(final ItemStack stack, final boolean ignoreDamageValue)
    {
        final ItemStorage checkItem = new ItemStorage(stack, ignoreDamageValue);

        return content.getOrDefault(checkItem, 0);
    }

    @Override
    public boolean hasItemStack(@NotNull final Predicate<ItemStack> itemStackSelectionPredicate)
    {
        for (final Map.Entry<ItemStorage, Integer> entry : content.entrySet())
        {
            if (itemStackSelectionPredicate.test(entry.getKey().getItemStack()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the content of the Rack
     *
     * @return the map of content.
     */
    public Map<ItemStorage, Integer> getAllContent()
    {
        return content;
    }

    @Override
    public void upgradeItemStorage()
    {
        ++size;
        final RackInventory tempInventory = new RackInventory(DEFAULT_SIZE + size * SLOT_PER_LINE);
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            tempInventory.setStackInSlot(slot, inventory.getStackInSlot(slot));
        }

        inventory = tempInventory;
        final BlockState state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 0x03);

        if (main && combinedHandler == null && getOtherChest() != null)
        {
            combinedHandler = new CombinedInvWrapper(inventory, getOtherChest().getInventory());
        }
    }

    @Override
    public int getItemCount(final Predicate<ItemStack> predicate)
    {
        for (final Map.Entry<ItemStorage, Integer> entry : content.entrySet())
        {
            if (predicate.test(entry.getKey().getItemStack()))
            {
                return entry.getValue();
            }
        }
        return 0;
    }

    @Override
    public void updateItemStorage()
    {
        if (world != null && !world.isRemote)
        {
            final boolean empty = content.isEmpty();
            updateContent();

            if ((empty && !content.isEmpty()) || !empty && content.isEmpty())
            {
                updateBlockState();
            }
            markDirty();
        }
    }

    /**
     * Just do the content update.
     */
    private void updateContent()
    {
        content.clear();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);

            if (ItemStackUtils.isEmpty(stack))
            {
                continue;
            }

            final ItemStorage storage = new ItemStorage(stack.copy());
            int amount = ItemStackUtils.getSize(stack);
            if (content.containsKey(storage))
            {
                amount += content.remove(storage);
            }
            content.put(storage, amount);
        }
    }

    @Override
    protected void updateBlockState()
    {
        if (world != null && world.getBlockState(pos).getBlock() instanceof AbstractBlockMinecoloniesRack)
        {
            if (!main && !single && getOtherChest() != null && !getOtherChest().isMain())
            {
                main = true;
            }

            if ((main || single))
            {
                final BlockState typeHere;
                final BlockState typeNeighbor;
                if (content.isEmpty() && (getOtherChest() == null || getOtherChest().isEmpty()))
                {
                    if (getOtherChest() != null && world.getBlockState(this.pos.subtract(relativeNeighbor)).getBlock() instanceof AbstractBlockMinecoloniesRack)
                    {

                        typeHere = world.getBlockState(pos).with(AbstractBlockMinecoloniesRack.VARIANT, RackType.EMPTYAIR);
                        typeNeighbor = world.getBlockState(this.pos.subtract(relativeNeighbor)).with(AbstractBlockMinecoloniesRack.VARIANT, RackType.DEFAULTDOUBLE)
                                         .with(AbstractBlockMinecoloniesRack.FACING, BlockPosUtil.getFacing(pos, this.pos.subtract(relativeNeighbor)));
                    }
                    else
                    {
                        typeHere = world.getBlockState(pos).with(AbstractBlockMinecoloniesRack.VARIANT, RackType.DEFAULT);
                        typeNeighbor = null;
                    }
                }
                else
                {
                    if (getOtherChest() != null && world.getBlockState(this.pos.subtract(relativeNeighbor)).getBlock() instanceof AbstractBlockMinecoloniesRack)
                    {
                        typeHere = world.getBlockState(pos).with(AbstractBlockMinecoloniesRack.VARIANT, RackType.EMPTYAIR);
                        typeNeighbor = world.getBlockState(this.pos.subtract(relativeNeighbor)).with(AbstractBlockMinecoloniesRack.VARIANT, RackType.FULLDOUBLE)
                                         .with(AbstractBlockMinecoloniesRack.FACING, BlockPosUtil.getFacing(pos, this.pos.subtract(relativeNeighbor)));
                    }
                    else
                    {
                        typeHere = world.getBlockState(pos).with(AbstractBlockMinecoloniesRack.VARIANT, RackType.FULL);
                        typeNeighbor = null;
                    }
                }

                // This here avoids that two racks can be main at the same time.
                if (this.isMain() && getOtherChest() != null && getOtherChest().isMain())
                {
                    getOtherChest().setMain(false);
                }

                if (!world.getBlockState(pos).equals(typeHere))
                {
                    world.setBlockState(pos, typeHere);
                }
                if (typeNeighbor != null)
                {
                    if (!world.getBlockState(this.pos.subtract(relativeNeighbor)).equals(typeHere))
                    {
                        world.setBlockState(this.pos.subtract(relativeNeighbor), typeNeighbor);
                    }
                }
            }
            else
            {
                getOtherChest().updateBlockState();
            }
        }
    }

    @Override
    public AbstractTileEntityRack getOtherChest()
    {
        if (relativeNeighbor == null || world == null)
        {
            return null;
        }
        final TileEntity tileEntity = world.getTileEntity(pos.subtract(relativeNeighbor));
        if (tileEntity instanceof TileEntityRack && !(tileEntity instanceof AbstractTileEntityColonyBuilding))
        {
            if (!this.getPos().equals(((TileEntityRack) tileEntity).getNeighbor()))
            {
                ((AbstractTileEntityRack) tileEntity).setNeighbor(this.getPos());
            }
            return (AbstractTileEntityRack) tileEntity;
        }

        setSingle(true);
        relativeNeighbor = null;
        return null;
    }

    @Override
    public boolean isEmpty()
    {
        return content.isEmpty();
    }

    @Override
    public void read(final CompoundNBT compound)
    {
        super.read(compound);
        if (compound.keySet().contains(TAG_SIZE))
        {
            size = compound.getInt(TAG_SIZE);
            if (size > 0)
            {
                inventory = new RackInventory(DEFAULT_SIZE + size * SLOT_PER_LINE);
            }
        }

        if (compound.keySet().contains(TAG_NEIGHBOR))
        {
            final BlockPos neighbor = BlockPosUtil.read(compound, TAG_NEIGHBOR);
            if (neighbor != BlockPos.ZERO)
            {
                relativeNeighbor = pos.subtract(neighbor);
            }
        }
        else if (compound.keySet().contains(TAG_RELATIVE_NEIGHBOR))
        {
            relativeNeighbor = BlockPosUtil.read(compound, TAG_RELATIVE_NEIGHBOR);
        }

        if (relativeNeighbor != null)
        {
            if (relativeNeighbor.getY() != 0)
            {
                relativeNeighbor = null;
            }
            else
            {
                setSingle(false);
            }
        }
        final ListNBT inventoryTagList = compound.getList(TAG_INVENTORY, TAG_COMPOUND);
        for (int i = 0; i < inventoryTagList.size(); ++i)
        {
            final CompoundNBT inventoryCompound = inventoryTagList.getCompound(i);
            if (!inventoryCompound.contains(TAG_EMPTY))
            {
                final ItemStack stack = ItemStack.read(inventoryCompound);
                inventory.setStackInSlot(i, stack);
            }
        }

        main = compound.getBoolean(TAG_MAIN);
        updateContent();

        this.inWarehouse = compound.getBoolean(TAG_IN_WAREHOUSE);
        if (compound.contains(TAG_POS))
        {
            this.buildingPos = BlockPosUtil.read(compound, TAG_POS);
        }
    }

    @NotNull
    @Override
    public CompoundNBT write(final CompoundNBT compound)
    {
        super.write(compound);
        compound.putInt(TAG_SIZE, size);

        if (relativeNeighbor != null)
        {
            BlockPosUtil.write(compound, TAG_RELATIVE_NEIGHBOR, relativeNeighbor);
        }
        @NotNull final ListNBT inventoryTagList = new ListNBT();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            @NotNull final CompoundNBT inventoryCompound = new CompoundNBT();
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty())
            {
                inventoryCompound.putBoolean(TAG_EMPTY, true);
            }
            else
            {
                stack.write(inventoryCompound);
            }
            inventoryTagList.add(inventoryCompound);
        }
        compound.put(TAG_INVENTORY, inventoryTagList);
        compound.putBoolean(TAG_MAIN, main);
        compound.putBoolean(TAG_IN_WAREHOUSE, inWarehouse);
        BlockPosUtil.write(compound, TAG_POS, buildingPos);
        return compound;
    }

    @Override
    public SUpdateTileEntityPacket getUpdatePacket()
    {
        final CompoundNBT compound = new CompoundNBT();
        return new SUpdateTileEntityPacket(this.pos, 0, this.write(compound));
    }

    @NotNull
    @Override
    public CompoundNBT getUpdateTag()
    {
        return this.write(new CompoundNBT());
    }

    @Override
    public void onDataPacket(final NetworkManager net, final SUpdateTileEntityPacket packet)
    {
        this.read(packet.getNbtCompound());
    }

    @Override
    public void handleUpdateTag(final CompoundNBT tag)
    {
        this.read(tag);
    }

    @Override
    public void rotate(final Rotation rotationIn)
    {
        super.rotate(rotationIn);
        if (relativeNeighbor != null)
        {
            relativeNeighbor = relativeNeighbor.rotate(rotationIn);
        }
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull final Capability<T> capability, final Direction dir)
    {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
        {
            if (single)
            {
                return LazyOptional.of(() -> (T) inventory);
            }
            else if (getOtherChest() != null)
            {
                if (isMain())
                {
                    if (combinedHandler == null)
                    {
                        combinedHandler = new CombinedInvWrapper(inventory, getOtherChest().getInventory());
                    }
                    return LazyOptional.of(() -> (T) combinedHandler);
                }
                else
                {
                    if (getOtherChest().isMain())
                    {
                        return (LazyOptional<T>) getOtherChest().getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY);
                    }
                    else
                    {
                        this.main = true;

                        if (combinedHandler == null)
                        {
                            combinedHandler = new CombinedInvWrapper(inventory, getOtherChest().getInventory());
                        }
                        markDirty();
                        return LazyOptional.of(() -> (T) combinedHandler);
                    }
                }
            }
        }
        return super.getCapability(capability, dir);
    }

    /**
     * Get the neighbor of the entity.
     *
     * @return the position, a blockPos.
     */
    @Override
    public BlockPos getNeighbor()
    {
        if (relativeNeighbor == null)
        {
            return null;
        }
        return pos.subtract(relativeNeighbor);
    }

    /**
     * Define the neighbor for a block.
     *
     * @param neighbor the neighbor to define.
     */
    @Override
    public boolean setNeighbor(final BlockPos neighbor)
    {
        if (neighbor == null)
        {
            setSingle(true);
            this.relativeNeighbor = null;
            markDirty();
        }
        // Only allow horizontal neighbor's
        else if (this.pos.subtract(neighbor).getY() == 0)
        {
            this.relativeNeighbor = this.pos.subtract(neighbor);
            setSingle(false);
            markDirty();
            return true;
        }
        return false;
    }

    @Override
    public void markDirty()
    {
        WorldUtil.markChunkDirty(world, pos);
    }

    @Nullable
    @Override
    public Container createMenu(final int id, @NotNull final PlayerInventory inv, @NotNull final PlayerEntity player)
    {
        final PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        buffer.writeBlockPos(this.getPos());
        buffer.writeBlockPos(this.getOtherChest() == null ? BlockPos.ZERO : this.getOtherChest().getPos());

        return new ContainerRack(id, inv, buffer);
    }

    @NotNull
    @Override
    public ITextComponent getDisplayName()
    {
        return new StringTextComponent("Rack");
    }
}
