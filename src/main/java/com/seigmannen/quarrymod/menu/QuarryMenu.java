package com.seigmannen.quarrymod.menu;

import com.seigmannen.quarrymod.block.entity.QuarryBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Menyen (server- og klientsiden av GUI-en). Den holder slotsene og synkroniserer innhold og tall.
 *
 * Slot-rekkefølge i menyen:
 * 0 = pickaxe, 1 = fuel, 2–25 = lager, 26–34 = filter (ghost), 35–61 = inventory, 62–70 = hotbar.
 */
public class QuarryMenu extends AbstractContainerMenu {
	public static final int PICKAXE_SLOT = 0;
	public static final int FUEL_SLOT = 1;
	public static final int STORAGE_START = 2;
	public static final int STORAGE_END = STORAGE_START + QuarryBlockEntity.STORAGE_SIZE;   // 26 (eksklusiv)
	public static final int FILTER_START = STORAGE_END;
	public static final int FILTER_END = FILTER_START + QuarryBlockEntity.FILTER_SIZE;       // 35
	public static final int PLAYER_START = FILTER_END;
	public static final int PLAYER_END = PLAYER_START + 36;                                   // 71

	/** Knapp-ID som sendes med clickMenuButton når filterknappen trykkes. */
	public static final int BUTTON_CYCLE_FILTER = 0;

	private final Container container;
	private final ContainerData data;
	/** Bare satt på serveren (null på klienten). */
	private final @Nullable QuarryBlockEntity quarry;

	/** Klient: tomme containere som fylles av synkroniseringen fra serveren. */
	public QuarryMenu(int containerId, Inventory inventory) {
		this(containerId, inventory, new SimpleContainer(QuarryBlockEntity.CONTAINER_SIZE),
				new SimpleContainer(QuarryBlockEntity.FILTER_SIZE),
				new SimpleContainerData(QuarryBlockEntity.DATA_COUNT), null);
	}

	/** Server: kobles direkte til block entityen. */
	public QuarryMenu(int containerId, Inventory inventory, QuarryBlockEntity quarry) {
		this(containerId, inventory, quarry, quarry.getFilter(), quarry.getData(), quarry);
	}

	private QuarryMenu(int containerId, Inventory inventory, Container container, Container filter,
			ContainerData data, @Nullable QuarryBlockEntity quarry) {
		super(ModMenuTypes.QUARRY, containerId);
		checkContainerSize(container, QuarryBlockEntity.CONTAINER_SIZE);
		checkContainerSize(filter, QuarryBlockEntity.FILTER_SIZE);
		checkContainerDataCount(data, QuarryBlockEntity.DATA_COUNT);
		this.container = container;
		this.data = data;
		this.quarry = quarry;
		container.startOpen(inventory.player);

		// Pickaxe og fuel.
		this.addSlot(new PickaxeSlot(container, QuarryBlockEntity.PICKAXE_SLOT, 17, 20));
		this.addSlot(new FuelSlot(container, QuarryBlockEntity.FUEL_SLOT, 17, 56));

		// Lager 6x4.
		for (int r = 0; r < 4; r++) {
			for (int c = 0; c < 6; c++) {
				int index = QuarryBlockEntity.STORAGE_START + r * 6 + c;
				this.addSlot(new OutputSlot(container, index, 62 + c * 18, 18 + r * 18));
			}
		}

		// Filter 3x3.
		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 3; c++) {
				this.addSlot(new FilterSlot(filter, r * 3 + c, 116 + c * 18, 102 + r * 18));
			}
		}

		// Spillerens inventory (8, 166) og hotbar (8, 224 = 166 + 58).
		this.addStandardInventorySlots(inventory, 8, 166);

		// Status og filtermodus.
		this.addDataSlots(data);
	}

	public int getStatus() {
		return data.get(QuarryBlockEntity.DATA_STATUS);
	}

	/** Setter sammen de to 16-bit-halvdelene. Masken trengs fordi klienten leser dem som (signed) short. */
	public int getRemainingBlocks() {
		int low = data.get(QuarryBlockEntity.DATA_BLOCKS_LOW) & 0xFFFF;
		int high = data.get(QuarryBlockEntity.DATA_BLOCKS_HIGH) & 0xFFFF;
		return (high << 16) | low;
	}

	public int getFilterMode() {
		return data.get(QuarryBlockEntity.DATA_FILTER_MODE);
	}

	/** Om alle filter-slotsene er tomme. Virker på klienten også, siden slotsene synkroniseres. */
	public boolean isFilterEmpty() {
		for (int i = FILTER_START; i < FILTER_END; i++) {
			if (this.slots.get(i).hasItem()) {
				return false;
			}
		}
		return true;
	}

	// Filterknappen: klienten sender knapp-ID-en, serveren bytter modus.
	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (id == BUTTON_CYCLE_FILTER) {
			if (quarry != null) {
				quarry.cycleFilterMode();
			}
			return true;
		}
		return false;
	}

	private static boolean isFilterSlot(int slotId) {
		return slotId >= FILTER_START && slotId < FILTER_END;
	}

	// Ghost-slots: klikk med item = legg inn en kopi (antall 1), klikk med tom hånd = tøm.
	// Spilleren beholder alltid itemet sitt.
	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		if (isFilterSlot(slotId)) {
			if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) {
				ItemStack carried = this.getCarried();
				this.slots.get(slotId).set(carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
			}
			return;
		}
		super.clicked(slotId, button, input, player);
	}

	// Dra-plassering og dobbeltklikk skal ikke røre filter-slotsene.
	@Override
	public boolean canDragTo(Slot slot) {
		return !(slot instanceof FilterSlot) && super.canDragTo(slot);
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return !(slot instanceof FilterSlot) && super.canTakeItemForPickAll(stack, slot);
	}

	/**
	 * Shift-klikk. Fra quarrien: til spillerens inventory.
	 * Fra spilleren: pickaxe til pickaxe-slotten, fuel til fuel-slotten, ellers ingenting.
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem() || isFilterSlot(index)) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();

		if (index < FILTER_START) {
			if (!this.moveItemStackTo(stack, PLAYER_START, PLAYER_END, true)) {
				return ItemStack.EMPTY;
			}
		} else {
			boolean moved = false;
			if (QuarryBlockEntity.isPickaxe(stack)) {
				moved = this.moveItemStackTo(stack, PICKAXE_SLOT, PICKAXE_SLOT + 1, false);
			}
			// F.eks. trepickaxe er også fuel: prøv fuel-slotten hvis pickaxe-slotten er opptatt.
			if (!moved && QuarryBlockEntity.isFuel(stack)) {
				moved = this.moveItemStackTo(stack, FUEL_SLOT, FUEL_SLOT + 1, false);
			}
			if (!moved) {
				return ItemStack.EMPTY;
			}
		}

		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		if (stack.getCount() == original.getCount()) {
			return ItemStack.EMPTY;
		}
		slot.onTake(player, stack);
		return original;
	}

	@Override
	public boolean stillValid(Player player) {
		return container.stillValid(player);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		container.stopOpen(player);
	}

	// --- Slot-typer ---

	/** Bare pickaxes, maks 1. */
	private static class PickaxeSlot extends Slot {
		PickaxeSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return QuarryBlockEntity.isPickaxe(stack);
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}
	}

	/** Bare items som kan brennes i en ovn. */
	private static class FuelSlot extends Slot {
		FuelSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return QuarryBlockEntity.isFuel(stack);
		}
	}

	/** Lageret: spilleren kan ta ut, men ikke legge inn. */
	private static class OutputSlot extends Slot {
		OutputSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}
	}

	/** Ghost-slot i filteret. Styres bare av clicked() over; vanlig plukking/plassering er av. */
	private static class FilterSlot extends Slot {
		FilterSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}
	}
}
