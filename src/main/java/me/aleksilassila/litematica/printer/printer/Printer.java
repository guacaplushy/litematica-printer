package me.aleksilassila.litematica.printer.printer;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import me.aleksilassila.litematica.printer.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.interfaces.IClientPlayerInteractionManager;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.Date;

public class Printer extends PrinterUtils {
    private final MinecraftClient client;
    private final ClientPlayerEntity playerEntity;
    private final ClientWorld clientWorld;
    private WorldSchematic worldSchematic;

	public long lastPlaced = new Date().getTime();

	public boolean lockCamera = false;

	private boolean shouldPlaceWater;
	private boolean shouldPrintInAir;
	private boolean shouldReplaceFluids;

	public static class Queue {
		public static BlockPos neighbor;
		public static Direction side;
		public static Vec3d hitVec;
		public static boolean useShift;
	}

	public Printer(MinecraftClient client, ClientPlayerEntity playerEntity, ClientWorld clientWorld) {
        this.client = client;
        this.playerEntity = playerEntity;
        this.clientWorld = clientWorld;
    }

	/**
	 * @return true if block was placed.
	 */
	public boolean processBlock(BlockPos pos) {
		BlockState currentState = clientWorld.getBlockState(pos);
		BlockState requiredState = worldSchematic.getBlockState(pos);

		// Check if block should be just clicked (repeaters etc.)
		if (shouldClickBlock(currentState, requiredState)) {
			addQueuedPacket(pos, Direction.UP, Vec3d.ofCenter(pos), null, false);

			lastPlaced = new Date().getTime();
			return true;
		}

		// FIXME water and lava
		// Check if something should be placed in target block
		if (requiredState.isAir()
				|| requiredState.getMaterial().equals(Material.WATER)
				|| requiredState.getMaterial().equals(Material.LAVA)) return false;

		// Check if target block is empty
		if (!shouldPlaceWater)
			if (!currentState.isAir() && !currentState.contains(FluidBlock.LEVEL)) {
				if (!PrinterUtils.isDoubleSlab(requiredState)) return false;
				else if (PrinterUtils.isDoubleSlab(currentState)) return false;
			} else if (currentState.contains(FluidBlock.LEVEL)) {
				if (currentState.get(FluidBlock.LEVEL) == 0 && !shouldReplaceFluids) return false;
			}
		else {
			if (isWaterLogged(requiredState) && isWaterLogged(currentState)) return false;
			if (!isWaterLogged(requiredState) && !currentState.isAir()) return false;
		}

		// Check if can be placed in world
		if (!requiredState.canPlaceAt(clientWorld, pos)) return false;

		// Check if player is holding right block
		Item itemInHand = Implementation.getInventory(playerEntity).getMainHandStack().getItem();
		if (!itemInHand.equals(requiredItemInHand(requiredState, currentState))) {
			if (Implementation.getAbilities(playerEntity).creativeMode) {
				ItemStack required = new ItemStack(requiredItemInHand(requiredState, currentState));
				BlockEntity te = clientWorld.getBlockEntity(pos);

				// The creative mode pick block with NBT only works correctly
				// if the server world doesn't have a TileEntity in that position.
				// Otherwise it would try to write whatever that TE is into the picked ItemStack.
				if (GuiBase.isCtrlDown() && te != null && clientWorld.isAir(pos))
				{
					ItemUtils.storeTEInStack(required, te);
				}

				InventoryUtils.setPickedItemToHand(required, client);
				client.interactionManager.clickCreativeStack(playerEntity.getStackInHand(Hand.MAIN_HAND),
						36 + Implementation.getInventory(playerEntity).selectedSlot);

			} else {
				int slot = getBlockInventorySlot(requiredItemInHand(requiredState, currentState));

				if (slot == -1) {
					return false;
				}

				swapHandWithSlot(slot);
			}
		}

		return placeBlock(pos, requiredState, currentState);
	}

    public void print() {
		lockCamera = false;
		sendQueuedPackets();

		if (new Date().getTime() < lastPlaced + 1000.0 * LitematicaMixinMod.PRINTING_DELAY.getDoubleValue()) return;

		int range = LitematicaMixinMod.PRINTING_RANGE.getIntegerValue();
//		shouldPlaceWater = LitematicaMixinMod.PRINT_WATER.getBooleanValue();
		shouldPlaceWater = false;
		shouldPrintInAir = LitematicaMixinMod.PRINT_IN_AIR.getBooleanValue();
		shouldReplaceFluids = LitematicaMixinMod.REPLACE_FLUIDS.getBooleanValue();
		worldSchematic = SchematicWorldHandler.getSchematicWorld();

		forEachBlockInRadius:
		for (int y = -range; y < range + 1; y++) {
			for (int x = -range; x < range + 1; x++) {
				for (int z = -range; z < range + 1; z++) {
					BlockPos pos = playerEntity.getBlockPos().north(x).west(z).up(y);

					if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;


					if (processBlock(pos)) return;
				}
			}
		}
    }

    public void addQueuedPacket(BlockPos neighbor, Direction side, Vec3d hitVec, Direction playerShouldBeFacing, boolean useShift) {
		if (Queue.neighbor != null) return;

		lockCamera = true;

		sendLookPacket(playerShouldBeFacing);

		Queue.neighbor = neighbor;
		Queue.side = side;
		Queue.hitVec = hitVec;
		Queue.useShift = useShift;
	}

	public void sendQueuedPackets() {
		if (Queue.neighbor != null) {
			boolean wasSneaking = playerEntity.isSneaking();

			if (Queue.useShift && !wasSneaking)
				playerEntity.networkHandler.sendPacket(new ClientCommandC2SPacket(playerEntity, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
			else if (!Queue.useShift && wasSneaking)
				playerEntity.networkHandler.sendPacket(new ClientCommandC2SPacket(playerEntity, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));

			((IClientPlayerInteractionManager) client.interactionManager).rightClickBlock(Queue.neighbor,
					Queue.side.getOpposite(), Queue.hitVec);

			if (Queue.useShift && !wasSneaking)
				playerEntity.networkHandler.sendPacket(new ClientCommandC2SPacket(playerEntity, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
			else if (!Queue.useShift && wasSneaking)
				playerEntity.networkHandler.sendPacket(new ClientCommandC2SPacket(playerEntity, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));

			Queue.neighbor = null;
			Queue.hitVec = null;
			Queue.side = null;
		}
	}

	private void swapHandWithSlot(int slot) {
		ItemStack stack = Implementation.getInventory(playerEntity).getStack(slot);
		InventoryUtils.setPickedItemToHand(stack, client);
	}

	private int getBlockInventorySlot(Item item) {
    	Inventory inv = Implementation.getInventory(playerEntity);

    	for (int slot = 0; slot < inv.size(); slot++) {
    		if (inv.getStack(slot).getItem().equals(item)) return slot;
		}

    	return -1;
	}

    private boolean placeBlock(BlockPos pos, BlockState state, BlockState currentState) {
		Vec3d posVec = Vec3d.ofCenter(pos);

		Direction playerShouldBeFacing = getFacingDirection(state);
		Direction.Axis axis = availableAxis(state);
		int half = getBlockHalf(state, currentState);

		if (state.getBlock() instanceof SlabBlock) {
			System.out.println("Slab half: " + half);
		}

		for (Direction side : Direction.values()) {
			if (half == 1 && side.equals(Direction.DOWN)) continue;
			if (half == 0 && side.equals(Direction.UP)) continue;
			if (axis != null && side.getAxis() != axis) continue;
			if (isTorchOnWall(state) && playerShouldBeFacing != side) continue;
			if (state.getBlock() instanceof HopperBlock && playerShouldBeFacing != side.getOpposite()) continue;
			if ((state.getBlock() instanceof AbstractButtonBlock || state.getBlock() instanceof LeverBlock)
					&& isLeverOnWall(state)
					&& playerShouldBeFacing != side.getOpposite()) continue;

			BlockPos neighbor = pos.offset(side);

			if (!canBeClicked(neighbor)) {
				if (!shouldPrintInAir) continue;
				neighbor = pos;
			}

			Vec3d hitVec = posVec.add(Vec3d.of(side.getVector()).multiply(0.5));

			if (half == 1 && !side.equals(Direction.UP)) {
				hitVec = hitVec.add(0, 0.25, 0);
			} else if (half == 0 && !side.equals(Direction.DOWN)) {
				hitVec = hitVec.add(0, -0.25, 0);
			}

			boolean doubleChest = state.contains(ChestBlock.CHEST_TYPE) && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE;
			addQueuedPacket(neighbor, side, hitVec, playerShouldBeFacing, !doubleChest);

			lastPlaced = new Date().getTime();
			return true;
		}

		return false;
	}

	private void sendLookPacket(Direction playerShouldBeFacing) {
    	if (playerShouldBeFacing != null) {
			float yaw = Implementation.getYaw(playerEntity);
			float pitch = Implementation.getPitch(playerEntity);

			if (playerShouldBeFacing.getAxis().isHorizontal()) {
				yaw = playerShouldBeFacing.asRotation();
			} else {
				pitch = playerShouldBeFacing == Direction.DOWN ? 90 : -90;
			}

			Implementation.sendLookPacket(playerEntity, yaw, pitch);
		}
	}

    private Item requiredItemInHand(BlockState requiredState, BlockState currentState) {
//		// If block should be waterlogged
//		if (!currentState.isAir() && isWaterLogged(requiredState))
//			return Items.WATER_BUCKET;
//		else if (requiredState.getBlock().equals(Blocks.WATER))
//			return Items.WATER_BUCKET;
//		else
			return new ItemStack(requiredState.getBlock()).getItem();
	}

	private boolean canBeClicked(BlockPos pos)
	{
		return getOutlineShape(pos) != VoxelShapes.empty();
	}

	private boolean needsShift(BlockState state, BlockHitResult blockHitResult) {
		// FIXME needShift
//		Block block = state.getBlock();
//
//		ActionResult actionResult = state.onUse(client.world, client.player, Hand.MAIN_HAND, blockHitResult);
//		System.out.println("Result: " + actionResult.name());
//		if (actionResult.isAccepted()) {
//			System.out.println("ACCEPTED OR SOMETHING");
//			return true;
//		}



//		return block instanceof DoorBlock
//				|| block instanceof ChestBlock
//				|| block instanceof CraftingTableBlock
//				|| block instanceof AnvilBlock
//				|| block instanceof AbstractFurnaceBlock
//				|| block instanceof BarrelBlock
//				|| block instanceof ChestBlock
//				|| block instanceof BedBlock
//				|| block instanceof BellBlock
//				|| block instanceof BrewingStandBlock
//				|| block instanceof ChestBlock
//				|| block instanceof ChestBlock
//				|| block instanceof ChestBlock
//				|| block instanceof TrapdoorBlock;

		return true;
	}

	private VoxelShape getOutlineShape(BlockPos pos)
	{
		return getState(pos).getOutlineShape(clientWorld, pos);
	}

	private BlockState getState(BlockPos pos)
	{
		return clientWorld.getBlockState(pos);
	}
}