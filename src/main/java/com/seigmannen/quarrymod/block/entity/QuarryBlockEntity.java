package com.seigmannen.quarrymod.block.entity;

import com.seigmannen.quarrymod.block.QuarryBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
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
 */
public class QuarryBlockEntity extends BlockEntity {
	/** Hvor mange ticks det tar å mine én blokk (6 = diamantpickaxe på stein). Endre her for å justere farten. */
	public static final int TICKS_PER_BLOCK = 6;

	/** Størrelsen på området (15x15) og hvor langt det går til hver side fra midten (7). */
	private static final int SIZE = 15;
	private static final int RADIUS = SIZE / 2;

	/** Maks antall posisjoner som kan hoppes over i én tick (ett helt lag), for å unngå lag. */
	private static final int MAX_SKIPS_PER_TICK = SIZE * SIZE;

	/** Verktøyet dropsene regnes ut med: en vanlig diamantpickaxe uten enchantments. */
	private static final ItemStack MINING_TOOL = new ItemStack(Items.DIAMOND_PICKAXE);

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
			if (shouldSkip(targetState, level, target)) {
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
	private static boolean shouldSkip(BlockState state, Level level, BlockPos pos) {
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

	/** Knuser blokken og sender blokk-dropsene til toppen av quarrien. */
	private void mineBlock(ServerLevel level, BlockPos target, BlockState targetState) {
		// Hent block entity (f.eks. for kister) FØR blokken fjernes, siden noen loot tables trenger den.
		BlockEntity targetBlockEntity = level.getBlockEntity(target);

		// Regn ut dropsene som om en spiller knuste blokken med en diamantpickaxe.
		List<ItemStack> drops = Block.getDrops(targetState, level, target, targetBlockEntity, null, MINING_TOOL);

		// Vanlig knusing, men uten at blokken dropper noe selv (false). Dette viser partikler og lyd,
		// og innholdet i kister o.l. blir liggende på bakken der blokken stod, akkurat som i vanilla.
		// Ingen XP, siden det bare gis når en spiller knuser blokken.
		level.destroyBlock(target, false);

		// Spytt ut dropsene midt på toppen av quarrien, uten fart.
		double x = this.worldPosition.getX() + 0.5;
		double y = this.worldPosition.getY() + 1.0;
		double z = this.worldPosition.getZ() + 0.5;
		for (ItemStack drop : drops) {
			if (drop.isEmpty()) {
				continue;
			}
			ItemEntity item = new ItemEntity(level, x, y, z, drop, 0.0, 0.0, 0.0);
			item.setDefaultPickUpDelay();
			level.addFreshEntity(item);
		}
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
	}
}
