package com.seigmannen.quarrymod.block.entity;

import com.seigmannen.quarrymod.QuarryMod;
import com.seigmannen.quarrymod.block.QuarryBlock;
import com.seigmannen.quarrymod.compat.MineEverythingCompat;
import com.seigmannen.quarrymod.component.FillerStorage;
import com.seigmannen.quarrymod.component.ModDataComponents;
import com.seigmannen.quarrymod.item.ModItems;
import com.seigmannen.quarrymod.menu.QuarryMenu;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Block entity-en til quarrien. Her ligger all mining-logikken og dataene som skal huskes.
 *
 * Området er et "sjakkbrett" (15x15 uten area-oppgradering, opptil 65x65) med quarrien over midten:
 * - Kolonner lagres som 0 til størrelse - 1. Kolonne 0 er lengst til venstre sett fra en spiller som ser på fronten.
 * - Rader lagres som 0 til størrelse - 1. Rad 0 er lengst bak (motsatt side av fronten).
 *
 * Inventory: slot 0 = pickaxe, slot 1 = fuel, slot 2–25 = lager, slot 26 = speed-, slot 27 = area-oppgradering,
 * slot 28 = fyll-slot (tømmes inn i fyll-lageret hver tick).
 * BaseContainerBlockEntity gjør at innholdet droppes automatisk når blokken brytes.
 * WorldlyContainer styrer hva hoppere får ta ut / sette inn fra hver side.
 * Filteret er en egen liste med kopier og er IKKE en del av inventoryet (så det droppes ikke).
 * Fyll-lageret (opptil 4608 fyllblokker) er heller ikke en del av inventoryet. Det følger med quarry-itemet
 * når blokken brytes (data-komponent som loot table-en kopierer, som en shulker box).
 *
 * Væske: før hvert lag skannes laget + ringen rett utenfor. Væske (og "alltid-vann"-blokker som kelp)
 * fylles med fyllblokker (fyll-lageret først, så lageret), én om gangen i mining-fart. Waterlogged blokker tømmes for vann med en gang.
 * Mangler fyllblokker, venter quarrien (NEEDS_FILLER = blå front). Ringen mines aldri, så den blir en vegg.
 */
public class QuarryBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
	/**
	 * Fartsfaktor: tiden per blokk = tiden pickaxen bruker på stein i vanilla, ganget med denne.
	 * Indeks = speed-oppgraderingens nivå (0 = ingen, 1–5 = I–V). Høyere = tregere.
	 */
	private static final int[] SPEED_FACTORS = {10, 7, 5, 3, 2, 1};
	/** Speed V gir alltid instant mining: 1 tick per blokk, uansett pickaxe og Efficiency. */
	private static final int INSTANT_SPEED_LEVEL = 5;

	/** Stein (hardhet 1,5). Alle blokker mines med samme fart som denne. */
	private static final float REFERENCE_HARDNESS = 1.5F;
	private static final BlockState REFERENCE_BLOCK = Blocks.STONE.defaultBlockState();

	/** Fremdriften telles fra 0 til 1; litt slingringsmonn for avrundingsfeil i float. */
	private static final float PROGRESS_DONE = 1.0F - 1.0E-4F;

	/** Sidelengden på området. Indeks = area-oppgraderingens nivå (0 = ingen, 1–5 = I–V). Alltid oddetall. */
	private static final int[] AREA_SIZES = {15, 25, 35, 45, 55, 65};

	// --- Væskehåndtering ---
	/** Items som kan brukes som fyllblokker mot væske (data/quarrymod/tags/item/filler_blocks.json). */
	public static final TagKey<Item> FILLER_BLOCKS = TagKey.create(Registries.ITEM, QuarryMod.id("filler_blocks"));
	/** Hvor mange fyllblokker fyll-lageret har plass til totalt (72 stacks). */
	public static final int FILLER_CAPACITY = 4608;
	/** scanIndex-verdi som betyr "laget er ferdig skannet" (brukes for gamle lagringer midt i et lag). */
	private static final int SCAN_DONE = Integer.MAX_VALUE;

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
	public static final int STORAGE_END = STORAGE_START + STORAGE_SIZE;  // 26 (eksklusiv)
	public static final int SPEED_UPGRADE_SLOT = STORAGE_END;            // 26
	public static final int AREA_UPGRADE_SLOT = STORAGE_END + 1;         // 27
	public static final int FILLER_SLOT = STORAGE_END + 2;               // 28
	public static final int CONTAINER_SIZE = STORAGE_END + 3;            // 29
	public static final int FILTER_SIZE = 9;

	/** Slotsene en hopper under quarrien kan trekke fra (bare lageret). */
	private static final int[] STORAGE_SLOTS = IntStream.range(STORAGE_START, STORAGE_END).toArray();
	/** Slotten en hopper på baksiden kan sette inn i. */
	private static final int[] PICKAXE_SLOTS = {PICKAXE_SLOT};
	/** Slotten en hopper på venstre side kan sette inn i. */
	private static final int[] FUEL_SLOTS = {FUEL_SLOT};
	/** Slotten en hopper på høyre side kan sette inn i. */
	private static final int[] FILLER_SLOTS = {FILLER_SLOT};
	private static final int[] NO_SLOTS = new int[0];

	// --- Status (synkroniseres til GUI-en via ContainerData) ---
	public static final int STATUS_EMPTY = 0;    // ingen fuel
	public static final int STATUS_READY = 1;    // fuel, men ingen pickaxe
	public static final int STATUS_RUNNING = 2;  // fuel og pickaxe
	public static final int STATUS_NO_FILLER = 3; // venter på fyllblokker

	// --- Filtermodus ---
	public static final int FILTER_DISABLED = 0;
	public static final int FILTER_WHITELIST = 1;
	public static final int FILTER_BLACKLIST = 2;
	public static final int FILTER_MODE_COUNT = 3;
	/** false = drops som filtreres bort, slettes. true = de blir liggende der blokken ble minet. */
	public static final boolean FILTERED_ITEMS_DROP_ON_GROUND = false;

	/** Indeksene i ContainerData. */
	public static final int DATA_STATUS = 0;
	public static final int DATA_FILTER_MODE = 1;
	// Antall blokker fuelen rekker. ContainerData sendes som 16-bit (short), så tallet deles i to halvdeler.
	public static final int DATA_BLOCKS_LOW = 2;
	public static final int DATA_BLOCKS_HIGH = 3;
	// Antall i fyll-lageret. Maks 4608, så det får plass i én short.
	public static final int DATA_FILLER = 4;
	public static final int DATA_COUNT = 5;

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

	// Fyll-lageret: item-type -> antall. Rekkefølgen beholdes, så det som kom inn først, brukes først.
	private final Map<Item, Integer> fillerStorage = new LinkedHashMap<>();
	private int fillerTotal = 0;

	/** Tallene GUI-en trenger. Menyen sender dem automatisk til klienten når de endres. */
	private final ContainerData data = new ContainerData() {
		@Override
		public int get(int index) {
			return switch (index) {
				case DATA_STATUS -> getStatus();
				case DATA_FILTER_MODE -> filterMode;
				case DATA_BLOCKS_LOW -> getRemainingBlocks() & 0xFFFF;
				case DATA_BLOCKS_HIGH -> getRemainingBlocks() >>> 16;
				case DATA_FILLER -> fillerTotal;
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
	private int column = 0;    // 0 = lengst til venstre
	private int row = 0;       // 0 = lengst bak
	private int layer = 0;     // 0 = laget rett under quarrien (y - 1), 1 = y - 2, osv.
	// Sidelengden på laget som pågår. Settes når laget starter (0 = ikke startet ennå), og holdes fast
	// ut laget selv om area-oppgraderingen byttes, så det aldri blir hull eller dobbelt mining.
	private int layerSize = 0;
	// Hvor langt vi har kommet på blokken vi holder på med (0 til 1). Lagres som andel, slik at
	// fremdriften beholdes hvis pickaxen byttes, mens den nye farten gjelder for resten.
	private float progress = 0.0F;
	// Hvor langt skanningen av laget + ringen rundt har kommet (indeks i (layerSize + 2)²-rutenettet).
	private int scanIndex = 0;
	// Fremdrift (0 til 1) på fyllblokken som plasseres nå under skanningen.
	private float fillProgress = 0.0F;
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
		// Det som ligger i fyll-slotten, flyttes inn i fyll-lageret (også når quarrien står stille).
		quarry.pullFillerSlot();
		// NEEDS_FILLER er bare true de tickene quarrien faktisk venter på fyllblokker.
		boolean waitingForFiller = tickQuarry(serverLevel, pos, state, quarry);
		setNeedsFiller(level, pos, waitingForFiller);
	}

	/** Selve tikken. Returnerer true hvis quarrien står og venter på fyllblokker. */
	private static boolean tickQuarry(ServerLevel level, BlockPos pos, BlockState state, QuarryBlockEntity quarry) {
		if (quarry.finished) {
			// Sørg for at blokken viser "ferdig"-fronten (f.eks. for quarries som ble ferdige før FINISHED fantes).
			markFinished(level, pos, state);
			return false;
		}
		// Uten pickaxe står quarrien stille, men beholder posisjon og fremdrift.
		ItemStack pickaxe = quarry.items.get(PICKAXE_SLOT);
		if (pickaxe.isEmpty()) {
			return false;
		}
		// Last inn ett fuel-item med en gang det er pickaxe (også under pause), så GUI-en viser antall blokker
		// i stedet for "Empty". Poengene brukes først når en blokk faktisk mines.
		quarry.ensureFuel(level);
		// Redstone-signal = pause. Vi gjør ingenting mer, så posisjon og fremdrift beholdes som de er.
		if (state.getValue(QuarryBlock.POWERED)) {
			return false;
		}

		boolean waitingForFiller = false;
		int skipped = 0;
		while (true) {
			// Nytt lag: lås størrelsen fra area-oppgraderingen som står i nå.
			if (quarry.layerSize == 0) {
				quarry.layerSize = AREA_SIZES[ModItems.getAreaLevel(quarry.items.get(AREA_UPGRADE_SLOT))];
			}
			// Maks ett helt lag med gratis overhopp per tick, for å unngå lag.
			if (skipped >= quarry.layerSize * quarry.layerSize) {
				break;
			}
			BlockPos target = quarry.getTargetPos(state);

			// Har vi gått under bunnen av verden? Da er vi ferdige.
			// (Bedrock på det nederste laget mines bare hvis Mine (Almost) Everything og /mae tillater det, se shouldSkip.)
			if (target.getY() < level.getMinY()) {
				quarry.finished = true;
				quarry.progress = 0.0F;
				quarry.setChanged();
				markFinished(level, pos, state);
				return false;
			}

			// Nytt lag: skann laget + ringen rundt for væske før vi miner. Bare når vi ellers ville minet (fuel),
			// men fyllingen trekker ikke fuel.
			if (!quarry.isScanDone()) {
				if (!quarry.ensureFuel(level)) {
					break;
				}
				waitingForFiller = quarry.scanLayer(level, state, pickaxe);
				// Ikke ferdig (fyller, venter på fyllblokker eller chunk ikke lastet): fortsett neste tick.
				if (!quarry.isScanDone()) {
					break;
				}
			}

			// Er chunken der målet ligger ikke lastet? Vent her og prøv igjen senere.
			if (!level.isLoaded(target)) {
				break;
			}

			BlockState targetState = level.getBlockState(target);
			if (quarry.shouldSkip(targetState, level, target, pickaxe)) {
				// Hopp over gratis (koster ingen tid) og gå videre til neste posisjon.
				quarry.advance();
				skipped++;
				continue;
			}

			// En blokk som skal mines: sørg for fuel til hele blokken. Ellers står vi stille med fremdriften intakt.
			if (!quarry.ensureFuel(level)) {
				break;
			}

			// Legg til én tick med pickaxens fart.
			quarry.progress += 1.0F / quarry.ticksPerBlock(level, pickaxe);
			if (quarry.progress >= PROGRESS_DONE) {
				quarry.mineBlock(level, target, targetState, pickaxe);
				quarry.fuelStored -= POINTS_PER_BLOCK;
				quarry.advance();
			}
			break;
		}
		// Si fra til Minecraft at dataene er endret, slik at de blir lagret.
		quarry.setChanged();
		return waitingForFiller;
	}

	/**
	 * Hvor mange ticks én blokk tar med denne pickaxen og speed-oppgraderingen som står i nå:
	 * ceil(30 * 1,5 * fartsfaktor / miningSpeed), minst 1. (Vanilla: 30 * hardhet / fart.) Speed V gir alltid 1.
	 * Regnes ut hver tick, så et bytte av oppgradering gjelder med en gang, mens fremdriften (andel) beholdes.
	 */
	private int ticksPerBlock(ServerLevel level, ItemStack pickaxe) {
		int speedLevel = ModItems.getSpeedLevel(items.get(SPEED_UPGRADE_SLOT));
		if (speedLevel >= INSTANT_SPEED_LEVEL) {
			return 1;
		}
		int speedFactor = SPEED_FACTORS[speedLevel];

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

		int ticks = Mth.ceil(30.0F * REFERENCE_HARDNESS * speedFactor / miningSpeed);
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

	/** Setter NEEDS_FILLER (blå front) bare hvis verdien har endret seg. Leser staten på nytt, siden den kan ha endret seg i tikken. */
	private static void setNeedsFiller(Level level, BlockPos pos, boolean needsFiller) {
		BlockState current = level.getBlockState(pos);
		if (current.getBlock() instanceof QuarryBlock && current.getValue(QuarryBlock.NEEDS_FILLER) != needsFiller) {
			level.setBlock(pos, current.setValue(QuarryBlock.NEEDS_FILLER, needsFiller), Block.UPDATE_CLIENTS);
		}
	}

	/**
	 * Regner ut verdensposisjonen til ruten (column, row) på laget vi er på,
	 * ut fra hvilken vei quarrien vender (FACING).
	 */
	private BlockPos getTargetPos(BlockState state) {
		// Radius = hvor langt området går til hver side fra midten (7 for 15x15, 32 for 65x65).
		int radius = layerSize / 2;
		// Rad 0 er radius blokker bak, siste rad er radius blokker foran.
		// Kolonne 0 er radius blokker til venstre, siste kolonne radius til høyre.
		return getLayerPos(state, radius - row, radius - column);
	}

	/** Posisjonen på laget vi er på, gitt antall blokker bak og til venstre for midten (negativt = foran/høyre). */
	private BlockPos getLayerPos(BlockState state, int backOffset, int leftOffset) {
		Direction front = state.getValue(HorizontalDirectionalBlock.FACING);
		// En spiller som ser på fronten, ser i motsatt retning av fronten.
		// "Bak" quarrien er altså samme retning som spilleren ser.
		Direction back = front.getOpposite();
		// Venstre for spilleren: drei synsretningen 90 grader mot klokka.
		Direction left = back.getCounterClockWise();

		return this.worldPosition
				.below(1 + layer)
				.relative(back, backOffset)
				.relative(left, leftOffset);
	}

	/**
	 * Går til neste rute: venstre mot høyre, rad for rad mot fronten, og deretter ett lag ned.
	 */
	private void advance() {
		progress = 0.0F;
		column++;
		if (column >= layerSize) {
			column = 0;
			row++;
			if (row >= layerSize) {
				row = 0;
				layer++;
				// Neste lag henter størrelsen på nytt fra area-oppgraderingen, og skannes på nytt.
				layerSize = 0;
				scanIndex = 0;
			}
		}
	}

	// --- Væskehåndtering ---

	/** Om laget (pluss ringen rundt) er ferdig skannet. */
	private boolean isScanDone() {
		int side = layerSize + 2;
		return scanIndex >= side * side;
	}

	/**
	 * Skanner laget pluss ringen rett utenfor (f.eks. 17x17 for et 15x15-område), fra der vi slapp sist.
	 * Posisjoner uten væske koster ingen tid, og waterlogged blokker tømmes med en gang.
	 * Væske tar like lang tid å fylle som én blokk å mine, så skanningen stopper ved hver fylling.
	 * Stopper også hvis en posisjon ligger i en chunk som ikke er lastet.
	 * Returnerer true hvis den står og venter på fyllblokker.
	 */
	private boolean scanLayer(ServerLevel level, BlockState state, ItemStack pickaxe) {
		int side = layerSize + 2;
		// Ringen ligger én blokk lenger ut enn området.
		int radius = layerSize / 2 + 1;
		while (scanIndex < side * side) {
			int c = scanIndex % side;
			int r = scanIndex / side;
			BlockPos pos = getLayerPos(state, radius - r, radius - c);
			// Chunken er ikke lastet: vent her og fortsett neste tick.
			if (!level.isLoaded(pos)) {
				return false;
			}
			BlockState target = level.getBlockState(pos);
			// Waterlogged: fjern vannet, men la blokken stå. Bruker ingen fyllblokk, så det skjer med en gang.
			if (target.hasProperty(BlockStateProperties.WATERLOGGED) && target.getValue(BlockStateProperties.WATERLOGGED)) {
				level.setBlock(pos, target.setValue(BlockStateProperties.WATERLOGGED, false), Block.UPDATE_ALL);
				scanIndex++;
				continue;
			}
			// Ingen væske her: gratis videre.
			if (!isFluidBlock(target)) {
				scanIndex++;
				continue;
			}
			// Væske eller "alltid-vann"-blokk: vent hvis vi ikke har fyllblokker (posisjon og fremdrift beholdes).
			if (!hasFiller()) {
				return true;
			}
			// Samme tid som å mine én blokk, men uten fuel-kostnad og uten slitasje på pickaxen.
			fillProgress += 1.0F / ticksPerBlock(level, pickaxe);
			if (fillProgress >= PROGRESS_DONE) {
				BlockState filler = takeFiller();
				if (filler == null) {
					return true;
				}
				// Erstatter blokken direkte, så kelp, sjøgress o.l. forsvinner uten drops.
				level.setBlock(pos, filler, Block.UPDATE_ALL);
				fillProgress = 0.0F;
				scanIndex++;
			}
			return false;
		}
		return false;
	}

	/**
	 * Væske som skal fylles: blokker med væske, men uten WATERLOGGED-property. Det gjelder vann/lava,
	 * men også "alltid-vann"-blokker som kelp, sjøgress og boblesøyler (også fra andre mods).
	 */
	private static boolean isFluidBlock(BlockState state) {
		return !state.getFluidState().isEmpty() && !state.hasProperty(BlockStateProperties.WATERLOGGED);
	}

	/** Første lager-slot med en fyllblokk som kan plasseres, eller -1 hvis det ikke finnes noen. */
	private int findFillerSlot() {
		for (int i = STORAGE_START; i < STORAGE_END; i++) {
			ItemStack stack = items.get(i);
			if (isFillerBlock(stack) && stack.getItem() instanceof BlockItem) {
				return i;
			}
		}
		return -1;
	}

	/** Om det finnes en fyllblokk i fyll-lageret eller i lageret. */
	private boolean hasFiller() {
		return fillerTotal > 0 || findFillerSlot() >= 0;
	}

	/** Tar én fyllblokk: fra fyll-lageret først, ellers fra lageret. null hvis begge er tomme. */
	private @Nullable BlockState takeFiller() {
		Iterator<Map.Entry<Item, Integer>> iterator = fillerStorage.entrySet().iterator();
		if (iterator.hasNext()) {
			Map.Entry<Item, Integer> entry = iterator.next();
			BlockState filler = ((BlockItem) entry.getKey()).getBlock().defaultBlockState();
			if (entry.getValue() <= 1) {
				iterator.remove();
			} else {
				entry.setValue(entry.getValue() - 1);
			}
			fillerTotal--;
			return filler;
		}
		int slot = findFillerSlot();
		if (slot < 0) {
			return null;
		}
		ItemStack stack = items.get(slot);
		BlockState filler = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
		stack.shrink(1);
		if (stack.isEmpty()) {
			items.set(slot, ItemStack.EMPTY);
		}
		return filler;
	}

	/** Legger så mange som får plass av stacken i fyll-lageret (bare fyllblokker). Returnerer antallet som kom inn. */
	private int addToFillerStorage(ItemStack stack) {
		if (!isFillerBlock(stack)) {
			return 0;
		}
		int move = Math.min(stack.getCount(), FILLER_CAPACITY - fillerTotal);
		if (move <= 0) {
			return 0;
		}
		fillerStorage.merge(stack.getItem(), move, Integer::sum);
		fillerTotal += move;
		setChanged();
		return move;
	}

	/** Flytter det som ligger i fyll-slotten inn i fyll-lageret, så langt det er plass. */
	private void pullFillerSlot() {
		ItemStack stack = items.get(FILLER_SLOT);
		int moved = addToFillerStorage(stack);
		if (moved > 0) {
			stack.shrink(moved);
			if (stack.isEmpty()) {
				items.set(FILLER_SLOT, ItemStack.EMPTY);
			}
		}
	}

	/** Fyll-lageret i samme form som data-komponenten (brukes også ved lagring). */
	private FillerStorage getFillerStorage() {
		List<FillerStorage.Entry> entries = new ArrayList<>();
		fillerStorage.forEach((item, count) -> entries.add(new FillerStorage.Entry(BuiltInRegistries.ITEM.wrapAsHolder(item), count)));
		return new FillerStorage(entries);
	}

	/** Erstatter fyll-lageret. Bare blokk-items tas med, og aldri mer enn FILLER_CAPACITY totalt. */
	private void setFillerStorage(FillerStorage storage) {
		fillerStorage.clear();
		fillerTotal = 0;
		for (FillerStorage.Entry entry : storage.entries()) {
			int count = Math.min(entry.count(), FILLER_CAPACITY - fillerTotal);
			if (entry.item().value() instanceof BlockItem && count > 0) {
				fillerStorage.merge(entry.item().value(), count, Integer::sum);
				fillerTotal += count;
			}
		}
	}

	/** Blokker som quarrien ikke skal mine. */
	private boolean shouldSkip(BlockState state, Level level, BlockPos pos, ItemStack pickaxe) {
		// En hopper rett under quarrien (H8 på første lag) får stå, så den kan tømme lageret.
		if (pos.equals(this.worldPosition.below()) && state.getBlock() instanceof HopperBlock) {
			return true;
		}
		// Luft.
		if (state.isAir()) {
			return true;
		}
		// Væske og "alltid-vann"-blokker (som skanningen ikke fikk fylt). Waterlogged blokker mines som normalt.
		if (isFluidBlock(state)) {
			return true;
		}
		// Blokker som ikke kan knuses i vanilla (hardhet under 0), f.eks. bedrock og barrier.
		// Unntak med Mine (Almost) Everything: bedrock med netherite pickaxe, hvis /mae tillater det.
		// Reinforced deepslate, budding amethyst og spawner kan knuses i vanilla og mines som vanlige blokker;
		// loot table-en avgjør om de dropper seg selv (Mine (Almost) Everything sjekker pickaxe og /mae der).
		return state.getDestroySpeed(level, pos) < 0 && !MineEverythingCompat.canMineUnbreakable(level, state, pos, pickaxe);
	}

	/** Knuser blokken. Dropsene går inn i lageret, og det som ikke får plass, spyttes ut på toppen. */
	private void mineBlock(ServerLevel level, BlockPos target, BlockState targetState, ItemStack pickaxe) {
		// Hent block entity (f.eks. for kister) FØR blokken fjernes, siden noen loot tables trenger den.
		BlockEntity targetBlockEntity = level.getBlockEntity(target);
		// Hardheten må leses før blokken fjernes (brukes til slitasjen under).
		float hardness = targetState.getDestroySpeed(level, target);

		// Dropsene regnes ut med selve pickaxen, så Silk Touch og Fortune virker. Block entityen sendes med,
		// så en spawner beholder mob-typen når loot table-en kopierer data fra den (Mine (Almost) Everything).
		// Loot tables-ene til Mine (Almost) Everything sjekker selv pickaxen (netherite / Silk Touch på jern eller bedre).
		// Som i vanilla: blokker som krever riktig verktøy (f.eks. diamond ore med steinpickaxe) gir ingenting.
		// Unntak med Mine (Almost) Everything: blokker som moden lar pickaxen høste (f.eks. cobweb) godtas også.
		// Dropsene kommer fortsatt fra loot table-en med pickaxen som verktøy.
		boolean canHarvest = !targetState.requiresCorrectToolForDrops()
				|| pickaxe.isCorrectToolForDrops(targetState)
				|| MineEverythingCompat.canHarvestWith(level, targetState, target, pickaxe);
		List<ItemStack> drops = canHarvest
				? Block.getDrops(targetState, level, target, targetBlockEntity, null, pickaxe)
				: List.of();

		// Vanlig knusing, men uten at blokken dropper noe selv (false). Dette viser partikler og lyd,
		// og innholdet i kister o.l. blir liggende på bakken der blokken stod, akkurat som i vanilla.
		// Ingen XP, siden det bare gis når en spiller knuser blokken.
		level.destroyBlock(target, false);

		damagePickaxe(level, pickaxe, hardness);

		// Filteret gjelder bare blokkens egne drops (etter Silk Touch/Fortune), ikke kisteinnhold eller fuel-rester.
		for (ItemStack drop : drops) {
			// Fyllblokker går først i fyll-lageret, før filteret (også om de står på blacklist).
			drop.shrink(addToFillerStorage(drop));
			if (drop.isEmpty()) {
				continue;
			}
			if (passesFilter(drop)) {
				storeOrSpit(level, drop);
			} else if (FILTERED_ITEMS_DROP_ON_GROUND) {
				Block.popResource(level, target, drop);
			}
			// Ellers slettes dropen.
		}
	}

	/** Om dropen skal beholdes etter filtermodusen. Bare item-typen sammenlignes (ikke antall/komponenter). */
	private boolean passesFilter(ItemStack drop) {
		if (filterMode == FILTER_DISABLED) {
			return true;
		}
		boolean inFilter = false;
		for (ItemStack entry : filter.getItems()) {
			if (!entry.isEmpty() && ItemStack.isSameItem(entry, drop)) {
				inFilter = true;
				break;
			}
		}
		// Whitelist: bare det som står i filteret. Blacklist: alt unntatt det som står i filteret.
		return filterMode == FILTER_WHITELIST ? inFilter : !inFilter;
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
		for (int i = STORAGE_START; i < STORAGE_END && !rest.isEmpty(); i++) {
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
		for (int i = STORAGE_START; i < STORAGE_END && !rest.isEmpty(); i++) {
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
	 * Med pickaxe: No filler hvis den venter på fyllblokker, ellers Running (antall blokker) hvis noe er lastet,
	 * ellers Empty.
	 */
	public int getStatus() {
		boolean hasLoadedFuel = getRemainingBlocks() > 0;
		if (items.get(PICKAXE_SLOT).isEmpty()) {
			return hasLoadedFuel || !items.get(FUEL_SLOT).isEmpty() ? STATUS_READY : STATUS_EMPTY;
		}
		BlockState state = getBlockState();
		if (state.hasProperty(QuarryBlock.NEEDS_FILLER) && state.getValue(QuarryBlock.NEEDS_FILLER)) {
			return STATUS_NO_FILLER;
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

	/** Items fra fyllblokk-taggen som kan plasseres som blokk (fyll-slotten og fyll-lageret godtar bare disse). */
	public static boolean isFillerBlock(ItemStack stack) {
		return !stack.isEmpty() && stack.is(FILLER_BLOCKS) && stack.getItem() instanceof BlockItem;
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
	// (Hoppere stoppes uansett av canPlaceItemThroughFace.)
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return switch (slot) {
			case PICKAXE_SLOT -> isPickaxe(stack);
			case FUEL_SLOT -> isFuel(stack);
			case SPEED_UPGRADE_SLOT -> ModItems.getSpeedLevel(stack) > 0;
			case AREA_UPGRADE_SLOT -> ModItems.getAreaLevel(stack) > 0;
			case FILLER_SLOT -> isFillerBlock(stack);
			default -> false;
		};
	}

	// --- Hoppere (WorldlyContainer) ---

	// Undersiden: lageret (bare uttak). Baksiden: pickaxe-slotten (bare innsetting).
	// Venstre: fuel-slotten. Høyre: fyll-slotten (bare innsetting). Fronten og toppen: ingenting.
	// Venstre/høyre er sett fra en spiller som står foran quarrien og ser på fronten.
	// Upgrade-slotsene er aldri med, så hoppere verken setter inn i eller trekker ut av dem.
	@Override
	public int[] getSlotsForFace(Direction side) {
		if (side == Direction.DOWN) {
			return STORAGE_SLOTS;
		}
		if (side == getBackSide()) {
			return PICKAXE_SLOTS;
		}
		if (side == getLeftSide()) {
			return FUEL_SLOTS;
		}
		if (side == getLeftSide().getOpposite()) {
			return FILLER_SLOTS;
		}
		return NO_SLOTS;
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		// Bare en pickaxe gjennom baksiden, og bare når pickaxe-slotten er tom (maks 1).
		if (side == getBackSide()) {
			return slot == PICKAXE_SLOT && isPickaxe(stack) && items.get(PICKAXE_SLOT).isEmpty();
		}
		// Bare gyldig fuel gjennom venstre side.
		if (side == getLeftSide()) {
			return slot == FUEL_SLOT && isFuel(stack);
		}
		// Bare fyllblokker gjennom høyre side.
		if (side == getLeftSide().getOpposite()) {
			return slot == FILLER_SLOT && isFillerBlock(stack);
		}
		return false;
	}

	/** Baksiden er motsatt av fronten (FACING). */
	private Direction getBackSide() {
		return getBlockState().getValue(HorizontalDirectionalBlock.FACING).getOpposite();
	}

	/** Venstre side for en spiller som ser på fronten (samme retning som i getLayerPos). */
	private Direction getLeftSide() {
		return getBackSide().getCounterClockWise();
	}

	// Det som står under (hopper o.l.) kan trekke alt fra lageret, aldri fra de andre slotsene.
	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return side == Direction.DOWN && slot >= STORAGE_START && slot < STORAGE_END;
	}

	// --- Fyll-lageret på quarry-itemet (som en shulker box) ---

	// Når quarrien settes ned: hent fyll-lageret fra itemet.
	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		FillerStorage storage = components.get(ModDataComponents.FILLER_STORAGE);
		if (storage != null) {
			setFillerStorage(storage);
		}
	}

	// Når quarrien brytes: loot table-en kopierer komponenten herfra til itemet (bare når lageret ikke er tomt).
	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		if (fillerTotal > 0) {
			components.set(ModDataComponents.FILLER_STORAGE, getFillerStorage());
		}
	}

	// Fyll-lageret ligger som komponent på itemet, så det skal ikke også ligge i block entity-dataene der.
	@Override
	public void removeComponentsFromTag(ValueOutput output) {
		super.removeComponentsFromTag(output);
		output.discard("FillerStorage");
	}

	// --- Lagring ---

	// Skriver dataene våre når chunken eller verdenen lagres.
	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("Column", column);
		output.putInt("Row", row);
		output.putInt("Layer", layer);
		output.putInt("LayerSize", layerSize);
		output.putFloat("MiningProgress", progress);
		output.putInt("ScanIndex", scanIndex);
		output.putFloat("FillProgress", fillProgress);
		output.putBoolean("Finished", finished);
		output.putLong("FuelStored", fuelStored);
		ContainerHelper.saveAllItems(output, items);
		ContainerHelper.saveAllItems(output.child("Filter"), filter.getItems());
		output.putInt("FilterMode", filterMode);
		if (fillerTotal > 0) {
			output.store("FillerStorage", FillerStorage.CODEC, getFillerStorage());
		}
	}

	// Leser dataene tilbake når chunken lastes inn igjen.
	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		column = input.getIntOr("Column", 0);
		row = input.getIntOr("Row", 0);
		layer = input.getIntOr("Layer", 0);
		// Gamle lagringer uten LayerSize var alltid 15x15; har de startet på laget, fullføres det med 15.
		int oldDefault = column > 0 || row > 0 ? AREA_SIZES[0] : 0;
		layerSize = input.getIntOr("LayerSize", oldDefault);
		// Ny nøkkel (andel 0–1). Gamle lagringer med "Progress" i ticks starter blokken på nytt.
		progress = input.getFloatOr("MiningProgress", 0.0F);
		// Gamle lagringer uten ScanIndex som er midt i et lag: det laget skannes ikke, bare de neste.
		scanIndex = input.getIntOr("ScanIndex", column > 0 || row > 0 ? SCAN_DONE : 0);
		fillProgress = input.getFloatOr("FillProgress", 0.0F);
		finished = input.getBooleanOr("Finished", false);
		fuelStored = input.getLongOr("FuelStored", 0L);
		items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, items);
		filter.getItems().replaceAll(stack -> ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input.childOrEmpty("Filter"), filter.getItems());
		filterMode = Math.floorMod(input.getIntOr("FilterMode", FILTER_DISABLED), FILTER_MODE_COUNT);
		// Gamle lagringer uten fyll-lager starter med det tomt.
		setFillerStorage(input.read("FillerStorage", FillerStorage.CODEC).orElse(new FillerStorage(List.of())));
		// Den gamle "MineBottomLayer"-nøkkelen (fra knappen i 0.10.0-beta) leses ikke lenger og forsvinner ved neste lagring.
	}
}
