package com.seigmannen.quarrymod.block.entity;

import com.seigmannen.quarrymod.block.QuarryBlock;
import com.seigmannen.quarrymod.menu.QuarryMenu;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Block entity-en til quarrien. Her ligger all mining-logikken og dataene som skal huskes.
 *
 * Området er et 15x15 "sjakkbrett" med quarrien over H8:
 * - Kolonner A–O (lagres som 0–14). A er lengst til venstre sett fra en spiller som ser på fronten.
 * - Rader 1–15 (lagres som 0–14). Rad 1 er lengst bak (motsatt side av fronten).
 *
 * Inventory: slot 0 = pickaxe, slot 1 = fuel, slot 2–25 = lager.
 * BaseContainerBlockEntity gjør at innholdet droppes automatisk når blokken brytes.
 * WorldlyContainer styrer hva hoppere får ta ut / sette inn fra hver side.
 * Filteret er en egen liste med kopier og er IKKE en del av inventoryet (så det droppes ikke).
 */
public class QuarryBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
	/** Hvor mange ticks det tar å mine én blokk (6 = diamantpickaxe på stein). Endre her for å justere farten. */
	public static final int TICKS_PER_BLOCK = 6;

	/** Størrelsen på området (15x15) og hvor langt det går til hver side fra midten (7). */
	private static final int SIZE = 15;
	private static final int RADIUS = SIZE / 2;

	/** Maks antall posisjoner som kan hoppes over i én tick (ett helt lag), for å unngå lag. */
	private static final int MAX_SKIPS_PER_TICK = SIZE * SIZE;

	/** Verktøyet dropsene regnes ut med: en vanlig diamantpickaxe uten enchantments. */
	private static final ItemStack MINING_TOOL = new ItemStack(Items.DIAMOND_PICKAXE);

	// --- Inventory ---
	public static final int PICKAXE_SLOT = 0;
	public static final int FUEL_SLOT = 1;
	public static final int STORAGE_START = 2;
	public static final int STORAGE_SIZE = 24;
	public static final int CONTAINER_SIZE = STORAGE_START + STORAGE_SIZE; // 26
	public static final int FILTER_SIZE = 9;

	/** Slotsene en hopper under quarrien kan trekke fra (bare lageret). */
	private static final int[] STORAGE_SLOTS = IntStream.range(STORAGE_START, CONTAINER_SIZE).toArray();
	private static final int[] NO_SLOTS = new int[0];

	// --- Status (synkroniseres til GUI-en via ContainerData) ---
	public static final int STATUS_EMPTY = 0;    // ingen fuel
	public static final int STATUS_READY = 1;    // fuel, men ingen pickaxe
	public static final int STATUS_RUNNING = 2;  // fuel og pickaxe

	// --- Filtermodus ---
	public static final int FILTER_DISABLED = 0;
	public static final int FILTER_WHITELIST = 1;
	public static final int FILTER_BLACKLIST = 2;
	public static final int FILTER_MODE_COUNT = 3;

	/** Indeksene i ContainerData. */
	public static final int DATA_STATUS = 0;
	public static final int DATA_FILTER_MODE = 1;
	public static final int DATA_COUNT = 2;

	private NonNullList<ItemStack> items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);

	/** Filter-kopiene (antall 1). Når de endres, markeres block entityen som endret slik at de lagres. */
	private final SimpleContainer filter = new SimpleContainer(FILTER_SIZE) {
		@Override
		public void setChanged() {
			super.setChanged();
			QuarryBlockEntity.this.setChanged();
		}
	};
	private int filterMode = FILTER_DISABLED;

	/** Tallene GUI-en trenger. Menyen sender dem automatisk til klienten når de endres. */
	private final ContainerData data = new ContainerData() {
		@Override
		public int get(int index) {
			return switch (index) {
				case DATA_STATUS -> getStatus();
				case DATA_FILTER_MODE -> filterMode;
				default -> 0;
			};
		}

		@Override
		public void set(int index, int value) {
			if (index == DATA_FILTER_MODE) {
				filterMode = value;
			}
		}

		@Override
		public int getCount() {
			return DATA_COUNT;
		}
	};

	// --- Data som lagres ---
	private int column = 0;    // 0 = A, 14 = O
	private int row = 0;       // 0 = rad 1, 14 = rad 15
	private int layer = 0;     // 0 = laget rett under quarrien (y - 1), 1 = y - 2, osv.
	private int progress = 0;  // hvor mange ticks vi har jobbet på blokken vi holder på med
	private boolean finished = false;

	public QuarryBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.QUARRY, pos, state);
	}

	/**
	 * Kalles hver tick på serveren (se QuarryBlock.getTicker).
	 * Block entities tikker bare når chunken deres er lastet, så det får vi automatisk.
	 */
	public static void serverTick(Level level, BlockPos pos, BlockState state, QuarryBlockEntity quarry) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (quarry.finished) {
			// Sørg for at blokken viser "ferdig"-fronten (f.eks. for quarries som ble ferdige før FINISHED fantes).
			markFinished(level, pos, state);
			return;
		}
		// Redstone-signal = pause. Vi gjør ingenting, så posisjon og fremdrift beholdes som de er.
		if (state.getValue(QuarryBlock.POWERED)) {
			return;
		}

		int skipped = 0;
		while (skipped < MAX_SKIPS_PER_TICK) {
			BlockPos target = quarry.getTargetPos(state);

			// Har vi gått under bunnen av verden? Da er vi ferdige.
			if (target.getY() < level.getMinY()) {
				quarry.finished = true;
				quarry.progress = 0;
				quarry.setChanged();
				markFinished(level, pos, state);
				return;
			}

			// Er chunken der målet ligger ikke lastet? Vent her og prøv igjen senere.
			if (!level.isLoaded(target)) {
				break;
			}

			BlockState targetState = level.getBlockState(target);
			if (quarry.shouldSkip(targetState, level, target)) {
				// Hopp over gratis (koster ingen tid) og gå videre til neste posisjon.
				quarry.advance();
				skipped++;
				continue;
			}

			// En blokk som skal mines: tell opp én tick.
			quarry.progress++;
			if (quarry.progress >= TICKS_PER_BLOCK) {
				quarry.mineBlock(serverLevel, target, targetState);
				quarry.advance();
			}
			break;
		}
		// Si fra til Minecraft at dataene er endret, slik at de blir lagret.
		quarry.setChanged();
	}

	/** Setter FINISHED=true på blokken, slik at fronten bytter til "ferdig"-teksturen. */
	private static void markFinished(Level level, BlockPos pos, BlockState state) {
		if (!state.getValue(QuarryBlock.FINISHED)) {
			level.setBlock(pos, state.setValue(QuarryBlock.FINISHED, true), Block.UPDATE_CLIENTS);
		}
	}

	/**
	 * Regner ut verdensposisjonen til ruten (column, row) på laget vi er på,
	 * ut fra hvilken vei quarrien vender (FACING).
	 */
	private BlockPos getTargetPos(BlockState state) {
		Direction front = state.getValue(HorizontalDirectionalBlock.FACING);
		// En spiller som ser på fronten, ser i motsatt retning av fronten.
		// "Bak" quarrien er altså samme retning som spilleren ser.
		Direction back = front.getOpposite();
		// Venstre for spilleren: drei synsretningen 90 grader mot klokka.
		Direction left = back.getCounterClockWise();

		// Rad 1 (row 0) er 7 blokker bak, rad 15 (row 14) er 7 blokker foran.
		int backOffset = RADIUS - row;
		// Kolonne A (column 0) er 7 blokker til venstre, kolonne O (column 14) 7 til høyre.
		int leftOffset = RADIUS - column;

		return this.worldPosition
				.below(1 + layer)
				.relative(back, backOffset)
				.relative(left, leftOffset);
	}

	/**
	 * Går til neste rute: A1, B1 … O1, A2 … O15, og deretter ett lag ned.
	 */
	private void advance() {
		progress = 0;
		column++;
		if (column >= SIZE) {
			column = 0;
			row++;
			if (row >= SIZE) {
				row = 0;
				layer++;
			}
		}
	}

	/** Blokker som quarrien ikke skal mine. */
	private boolean shouldSkip(BlockState state, Level level, BlockPos pos) {
		// En hopper rett under quarrien (H8 på første lag) får stå, så den kan tømme lageret.
		if (pos.equals(this.worldPosition.below()) && state.getBlock() instanceof HopperBlock) {
			return true;
		}
		// Luft.
		if (state.isAir()) {
			return true;
		}
		// Rene væsker (vann, lava). Waterlogged blokker er ikke LiquidBlock, så de mines som normalt.
		// Boblesøyler (bubble column) er egentlig bare vann, så de hoppes også over.
		if (state.getBlock() instanceof LiquidBlock || state.is(Blocks.BUBBLE_COLUMN)) {
			return true;
		}
		// Blokker som ikke kan knuses (hardhet under 0), f.eks. bedrock og barrier.
		return state.getDestroySpeed(level, pos) < 0;
	}

	/** Knuser blokken. Dropsene går inn i lageret, og det som ikke får plass, spyttes ut på toppen. */
	private void mineBlock(ServerLevel level, BlockPos target, BlockState targetState) {
		// Hent block entity (f.eks. for kister) FØR blokken fjernes, siden noen loot tables trenger den.
		BlockEntity targetBlockEntity = level.getBlockEntity(target);

		// Regn ut dropsene som om en spiller knuste blokken med en diamantpickaxe.
		List<ItemStack> drops = Block.getDrops(targetState, level, target, targetBlockEntity, null, MINING_TOOL);

		// Vanlig knusing, men uten at blokken dropper noe selv (false). Dette viser partikler og lyd,
		// og innholdet i kister o.l. blir liggende på bakken der blokken stod, akkurat som i vanilla.
		// Ingen XP, siden det bare gis når en spiller knuser blokken.
		level.destroyBlock(target, false);

		// Det som ikke får plass i lageret, spyttes ut midt på toppen av quarrien, uten fart.
		double x = this.worldPosition.getX() + 0.5;
		double y = this.worldPosition.getY() + 1.0;
		double z = this.worldPosition.getZ() + 0.5;
		for (ItemStack drop : drops) {
			ItemStack rest = insertIntoStorage(drop);
			if (rest.isEmpty()) {
				continue;
			}
			ItemEntity item = new ItemEntity(level, x, y, z, rest, 0.0, 0.0, 0.0);
			item.setDefaultPickUpDelay();
			level.addFreshEntity(item);
		}
	}

	/**
	 * Legger stacken i lageret (slot 2–25): først oppå like stacks, så i tomme slots.
	 * Returnerer det som ikke fikk plass (tom stack hvis alt kom inn).
	 */
	private ItemStack insertIntoStorage(ItemStack stack) {
		ItemStack rest = stack.copy();
		// Runde 1: fyll opp eksisterende stacks av samme item.
		for (int i = STORAGE_START; i < CONTAINER_SIZE && !rest.isEmpty(); i++) {
			ItemStack existing = items.get(i);
			if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, rest)) {
				int move = Math.min(rest.getCount(), existing.getMaxStackSize() - existing.getCount());
				if (move > 0) {
					existing.grow(move);
					rest.shrink(move);
				}
			}
		}
		// Runde 2: tomme slots.
		for (int i = STORAGE_START; i < CONTAINER_SIZE && !rest.isEmpty(); i++) {
			if (items.get(i).isEmpty()) {
				items.set(i, rest.split(rest.getMaxStackSize()));
			}
		}
		return rest;
	}

	// --- Status og filter ---

	/** Status som vises i GUI-en. Foreløpig miner quarrien uansett status. */
	public int getStatus() {
		if (items.get(FUEL_SLOT).isEmpty()) {
			return STATUS_EMPTY;
		}
		if (items.get(PICKAXE_SLOT).isEmpty()) {
			return STATUS_READY;
		}
		return STATUS_RUNNING;
	}

	/** Disabled -> Whitelist -> Blacklist -> Disabled ... */
	public void cycleFilterMode() {
		filterMode = (filterMode + 1) % FILTER_MODE_COUNT;
		setChanged();
	}

	public SimpleContainer getFilter() {
		return filter;
	}

	public ContainerData getData() {
		return data;
	}

	public static boolean isPickaxe(ItemStack stack) {
		return stack.is(ItemTags.PICKAXES);
	}

	/** Samme sjekk som ovnen bruker: items med COOKING_FUEL-komponenten kan brennes. */
	public static boolean isFuel(ItemStack stack) {
		return stack.has(DataComponents.COOKING_FUEL);
	}

	// --- Container / meny ---

	@Override
	protected Component getDefaultName() {
		return Component.translatable("container.quarrymod.quarry");
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.items = items;
	}

	@Override
	public int getContainerSize() {
		return CONTAINER_SIZE;
	}

	@Override
	protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
		return new QuarryMenu(containerId, inventory, this);
	}

	// Hva som lovlig kan ligge i hver slot. Lageret fylles bare av quarrien selv.
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return switch (slot) {
			case PICKAXE_SLOT -> isPickaxe(stack);
			case FUEL_SLOT -> isFuel(stack);
			default -> false;
		};
	}

	// --- Hoppere (WorldlyContainer) ---

	// Bare undersiden gir tilgang, og bare til lageret.
	@Override
	public int[] getSlotsForFace(Direction side) {
		return side == Direction.DOWN ? STORAGE_SLOTS : NO_SLOTS;
	}

	// Ingenting kan settes inn fra noen side (pickaxe/fuel via hopper kommer senere).
	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		return false;
	}

	// Det som står under (hopper o.l.) kan trekke fra lageret, aldri fra pickaxe/fuel.
	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return side == Direction.DOWN && slot >= STORAGE_START && slot < CONTAINER_SIZE;
	}

	// --- Lagring ---

	// Skriver dataene våre når chunken eller verdenen lagres.
	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("Column", column);
		output.putInt("Row", row);
		output.putInt("Layer", layer);
		output.putInt("Progress", progress);
		output.putBoolean("Finished", finished);
		ContainerHelper.saveAllItems(output, items);
		ContainerHelper.saveAllItems(output.child("Filter"), filter.getItems());
		output.putInt("FilterMode", filterMode);
	}

	// Leser dataene tilbake når chunken lastes inn igjen.
	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		column = input.getIntOr("Column", 0);
		row = input.getIntOr("Row", 0);
		layer = input.getIntOr("Layer", 0);
		progress = input.getIntOr("Progress", 0);
		finished = input.getBooleanOr("Finished", false);
		items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, items);
		filter.getItems().replaceAll(stack -> ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input.childOrEmpty("Filter"), filter.getItems());
		filterMode = Math.floorMod(input.getIntOr("FilterMode", FILTER_DISABLED), FILTER_MODE_COUNT);
	}
}
