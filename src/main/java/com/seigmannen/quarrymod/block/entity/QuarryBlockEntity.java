package com.seigmannen.quarrymod.block.entity;

import com.seigmannen.quarrymod.block.QuarryBlock;
import com.seigmannen.quarrymod.menu.QuarryMenu;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
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
	/**
	 * Fartsfaktor: tiden per blokk = tiden pickaxen bruker på stein i vanilla, ganget med denne.
	 * Endre her for å justere farten (høyere = tregere).
	 */
	public static final int SPEED_FACTOR = 10;

	/** Stein (hardhet 1,5). Alle blokker mines med samme fart som denne. */
	private static final float REFERENCE_HARDNESS = 1.5F;
	private static final BlockState REFERENCE_BLOCK = Blocks.STONE.defaultBlockState();

	/** Fremdriften telles fra 0 til 1; litt slingringsmonn for avrundingsfeil i float. */
	private static final float PROGRESS_DONE = 1.0F - 1.0E-4F;

	/** Størrelsen på området (15x15) og hvor langt det går til hver side fra midten (7). */
	private static final int SIZE = 15;
	private static final int RADIUS = SIZE / 2;

	/** Maks antall posisjoner som kan hoppes over i én tick (ett helt lag), for å unngå lag. */
	private static final int MAX_SKIPS_PER_TICK = SIZE * SIZE;

	// --- Fuel ---
	/** Én smelting i en ovn (200 ticks brenntid) = 12 blokker, så hver tick brenntid gir 12 poeng. */
	private static final int POINTS_PER_BURN_TICK = 12;
	/** Hver minet blokk koster 200 poeng. Kull: 1600 * 12 / 200 = 96 blokker. */
	public static final int POINTS_PER_BLOCK = 200;

	// --- Inventory ---
	public static final int PICKAXE_SLOT = 0;
	public static final int FUEL_SLOT = 1;
	public static final int STORAGE_START = 2;
	public static final int STORAGE_SIZE = 24;
	public static final int CONTAINER_SIZE = STORAGE_START + STORAGE_SIZE; // 26
	public static final int FILTER_SIZE = 9;

	/** Slotsene en hopper under quarrien kan trekke fra (bare lageret). */
	private static final int[] STORAGE_SLOTS = IntStream.range(STORAGE_START, CONTAINER_SIZE).toArray();
	/** Slotten en hopper på baksiden kan sette inn i. */
	private static final int[] PICKAXE_SLOTS = {PICKAXE_SLOT};
	/** Slotten en hopper på venstre/høyre side kan sette inn i. */
	private static final int[] FUEL_SLOTS = {FUEL_SLOT};
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
	// Antall blokker fuelen rekker. ContainerData sendes som 16-bit (short), så tallet deles i to halvdeler.
	public static final int DATA_BLOCKS_LOW = 2;
	public static final int DATA_BLOCKS_HIGH = 3;
	public static final int DATA_COUNT = 4;

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
				case DATA_BLOCKS_LOW -> getRemainingBlocks() & 0xFFFF;
				case DATA_BLOCKS_HIGH -> getRemainingBlocks() >>> 16;
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
	// Hvor langt vi har kommet på blokken vi holder på med (0 til 1). Lagres som andel, slik at
	// fremdriften beholdes hvis pickaxen byttes, mens den nye farten gjelder for resten.
	private float progress = 0.0F;
	private boolean finished = false;
	// Internt fuel-lager i poeng (heltall, så vi slipper avrunding). Long i tilfelle mod-fuel med enorm brenntid.
	private long fuelStored = 0;

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
		// Uten pickaxe står quarrien stille, men beholder posisjon og fremdrift.
		ItemStack pickaxe = quarry.items.get(PICKAXE_SLOT);
		if (pickaxe.isEmpty()) {
			return;
		}
		// Last inn ett fuel-item med en gang det er pickaxe (også under pause), så GUI-en viser antall blokker
		// i stedet for "Empty". Poengene brukes først når en blokk faktisk mines.
		quarry.ensureFuel(serverLevel);
		// Redstone-signal = pause. Vi gjør ingenting mer, så posisjon og fremdrift beholdes som de er.
		if (state.getValue(QuarryBlock.POWERED)) {
			return;
		}

		int skipped = 0;
		while (skipped < MAX_SKIPS_PER_TICK) {
			BlockPos target = quarry.getTargetPos(state);

			// Har vi gått under bunnen av verden? Da er vi ferdige.
			if (target.getY() < level.getMinY()) {
				quarry.finished = true;
				quarry.progress = 0.0F;
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

			// En blokk som skal mines: sørg for fuel til hele blokken. Ellers står vi stille med fremdriften intakt.
			if (!quarry.ensureFuel(serverLevel)) {
				break;
			}

			// Legg til én tick med pickaxens fart.
			quarry.progress += 1.0F / ticksPerBlock(serverLevel, pickaxe);
			if (quarry.progress >= PROGRESS_DONE) {
				quarry.mineBlock(serverLevel, target, targetState, pickaxe);
				quarry.fuelStored -= POINTS_PER_BLOCK;
				quarry.advance();
			}
			break;
		}
		// Si fra til Minecraft at dataene er endret, slik at de blir lagret.
		quarry.setChanged();
	}

	/**
	 * Hvor mange ticks én blokk tar med denne pickaxen:
	 * ceil(30 * 1,5 * SPEED_FACTOR / miningSpeed), minst 1. (Vanilla: 30 * hardhet / fart med riktig verktøy.)
	 */
	private static int ticksPerBlock(ServerLevel level, ItemStack pickaxe) {
		// Farten på stein hentes fra itemets Tool-komponent, så det virker for alle pickaxer (også fra andre mods).
		Tool tool = pickaxe.get(DataComponents.TOOL);
		float miningSpeed = tool != null ? tool.getMiningSpeed(REFERENCE_BLOCK) : 1.0F;

		// Efficiency: vanilla legger til nivå² + 1 når farten er over 1 (via spillerens attributt).
		// Enchantments er data-drevne, så vi slår opp Efficiency i registeret.
		if (miningSpeed > 1.0F) {
			int efficiency = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
					.get(Enchantments.EFFICIENCY)
					.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, pickaxe))
					.orElse(0);
			if (efficiency > 0) {
				miningSpeed += efficiency * efficiency + 1;
			}
		}

		int ticks = Mth.ceil(30.0F * REFERENCE_HARDNESS * SPEED_FACTOR / miningSpeed);
		return Math.max(1, ticks);
	}

	/**
	 * Har lageret under 200 poeng, brennes ett item fra fuel-slotten. Returnerer om det nå er nok til én blokk.
	 * Siden de 200 poengene først trekkes når blokken er ferdig, skjer påfyllet i praksis før en ny blokk.
	 */
	private boolean ensureFuel(ServerLevel level) {
		if (fuelStored >= POINTS_PER_BLOCK) {
			return true;
		}
		ItemStack fuel = items.get(FUEL_SLOT);
		int burnTime = getBurnTime(level, fuel);
		if (burnTime <= 0) {
			return false;
		}
		fuelStored += (long) burnTime * POINTS_PER_BURN_TICK;

		// Som i ovnen: hent resten (f.eks. bøtta etter lava) før stacken krympes.
		ItemStackTemplate remainder = fuel.getItem().getCraftingRemainder();
		fuel.shrink(1);
		if (fuel.isEmpty()) {
			items.set(FUEL_SLOT, ItemStack.EMPTY);
		}
		// Resten går i lageret, eller ut på toppen hvis det er fullt.
		if (remainder != null) {
			storeOrSpit(level, remainder.create());
		}
		setChanged();
		return fuelStored >= POINTS_PER_BLOCK;
	}

	/**
	 * Brenntiden i ticks, regnet ut på samme måte som ovnen: fra COOKING_FUEL-komponenten.
	 * Den er data-drevet, så fuel fra andre mods virker også. 0 hvis itemet ikke er fuel.
	 */
	private int getBurnTime(ServerLevel level, ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		return ResolvableInt.getFromItem(stack, DataComponents.COOKING_FUEL, CookingFuel::burnTime, getLootContext(level), 0);
	}

	/**
	 * Hvor mange blokker den innlastede fuelen rekker: fuel-lageret / 200, rundet ned.
	 * Items som fortsatt ligger i fuel-slotten telles ikke med.
	 */
	public int getRemainingBlocks() {
		return (int) Math.min(fuelStored / POINTS_PER_BLOCK, Integer.MAX_VALUE);
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
		progress = 0.0F;
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
	private void mineBlock(ServerLevel level, BlockPos target, BlockState targetState, ItemStack pickaxe) {
		// Hent block entity (f.eks. for kister) FØR blokken fjernes, siden noen loot tables trenger den.
		BlockEntity targetBlockEntity = level.getBlockEntity(target);
		// Hardheten må leses før blokken fjernes (brukes til slitasjen under).
		float hardness = targetState.getDestroySpeed(level, target);

		// Dropsene regnes ut med selve pickaxen, så Silk Touch og Fortune virker.
		// Som i vanilla: blokker som krever riktig verktøy (f.eks. diamond ore med steinpickaxe) gir ingenting.
		List<ItemStack> drops = !targetState.requiresCorrectToolForDrops() || pickaxe.isCorrectToolForDrops(targetState)
				? Block.getDrops(targetState, level, target, targetBlockEntity, null, pickaxe)
				: List.of();

		// Vanlig knusing, men uten at blokken dropper noe selv (false). Dette viser partikler og lyd,
		// og innholdet i kister o.l. blir liggende på bakken der blokken stod, akkurat som i vanilla.
		// Ingen XP, siden det bare gis når en spiller knuser blokken.
		level.destroyBlock(target, false);

		damagePickaxe(level, pickaxe, hardness);

		for (ItemStack drop : drops) {
			storeOrSpit(level, drop);
		}
	}

	/** Legger stacken i lageret. Det som ikke får plass, spyttes ut midt på toppen av quarrien, uten fart. */
	private void storeOrSpit(ServerLevel level, ItemStack stack) {
		ItemStack rest = insertIntoStorage(stack);
		if (rest.isEmpty()) {
			return;
		}
		double x = this.worldPosition.getX() + 0.5;
		double y = this.worldPosition.getY() + 1.0;
		double z = this.worldPosition.getZ() + 0.5;
		ItemEntity item = new ItemEntity(level, x, y, z, rest, 0.0, 0.0, 0.0);
		item.setDefaultPickUpDelay();
		level.addFreshEntity(item);
	}

	/**
	 * Slitasje som når en spiller miner (Item.mineBlock): damagePerBlock fra Tool-komponenten,
	 * men ingenting for blokker med hardhet 0. hurtAndBreak med ServerLevel tar hensyn til Unbreaking
	 * uten spiller. Går pickaxen i stykker, blir slotten tom og quarrien stopper.
	 */
	private void damagePickaxe(ServerLevel level, ItemStack pickaxe, float hardness) {
		Tool tool = pickaxe.get(DataComponents.TOOL);
		if (tool == null || hardness == 0.0F || tool.damagePerBlock() <= 0) {
			return;
		}
		pickaxe.hurtAndBreak(tool.damagePerBlock(), level, null, brokenItem ->
				level.playSound(null, this.worldPosition, SoundEvents.ITEM_BREAK.value(), SoundSource.BLOCKS, 0.8F,
						0.8F + level.getRandom().nextFloat() * 0.4F));
		// Stacken endres direkte i slotten; si fra slik at den lagres (menyen ser endringen selv).
		if (pickaxe.isEmpty()) {
			items.set(PICKAXE_SLOT, ItemStack.EMPTY);
		}
		setChanged();
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

	/**
	 * Status som vises i GUI-en.
	 * Uten pickaxe: Ready hvis det finnes fuel (lastet eller i slotten), ellers Empty.
	 * Med pickaxe: Running (antall blokker) hvis noe er lastet, ellers Empty.
	 */
	public int getStatus() {
		boolean hasLoadedFuel = getRemainingBlocks() > 0;
		if (items.get(PICKAXE_SLOT).isEmpty()) {
			return hasLoadedFuel || !items.get(FUEL_SLOT).isEmpty() ? STATUS_READY : STATUS_EMPTY;
		}
		return hasLoadedFuel ? STATUS_RUNNING : STATUS_EMPTY;
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

	// Undersiden: lageret (bare uttak). Baksiden: pickaxe-slotten (bare innsetting).
	// Venstre/høyre: fuel-slotten (bare innsetting). Fronten og toppen: ingenting.
	@Override
	public int[] getSlotsForFace(Direction side) {
		if (side == Direction.DOWN) {
			return STORAGE_SLOTS;
		}
		if (side == getBackSide()) {
			return PICKAXE_SLOTS;
		}
		if (isSideFace(side)) {
			return FUEL_SLOTS;
		}
		return NO_SLOTS;
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		// Bare en pickaxe gjennom baksiden, og bare når pickaxe-slotten er tom (maks 1).
		if (side == getBackSide()) {
			return slot == PICKAXE_SLOT && isPickaxe(stack) && items.get(PICKAXE_SLOT).isEmpty();
		}
		// Bare gyldig fuel gjennom venstre/høyre side.
		if (isSideFace(side)) {
			return slot == FUEL_SLOT && isFuel(stack);
		}
		return false;
	}

	/** Baksiden er motsatt av fronten (FACING). */
	private Direction getBackSide() {
		return getBlockState().getValue(HorizontalDirectionalBlock.FACING).getOpposite();
	}

	/** Venstre eller høyre side sett fra FACING (de to horisontale sidene som verken er front eller bak). */
	private boolean isSideFace(Direction side) {
		Direction front = getBlockState().getValue(HorizontalDirectionalBlock.FACING);
		return side == front.getClockWise() || side == front.getCounterClockWise();
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
		output.putFloat("MiningProgress", progress);
		output.putBoolean("Finished", finished);
		output.putLong("FuelStored", fuelStored);
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
		// Ny nøkkel (andel 0–1). Gamle lagringer med "Progress" i ticks starter blokken på nytt.
		progress = input.getFloatOr("MiningProgress", 0.0F);
		finished = input.getBooleanOr("Finished", false);
		fuelStored = input.getLongOr("FuelStored", 0L);
		items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, items);
		filter.getItems().replaceAll(stack -> ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input.childOrEmpty("Filter"), filter.getItems());
		filterMode = Math.floorMod(input.getIntOr("FilterMode", FILTER_DISABLED), FILTER_MODE_COUNT);
	}
}
