package com.seigmannen.quarrymod.block;

import com.seigmannen.quarrymod.block.entity.ModBlockEntities;
import com.seigmannen.quarrymod.block.entity.QuarryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Selve quarry-blokken. Fronten vender mot spilleren som plasserte den,
 * og selve mining-logikken ligger i QuarryBlockEntity.
 *
 * HorizontalDirectionalBlock gir oss FACING-propertyen (north/east/south/west)
 * og håndterer rotasjon/speiling (f.eks. når strukturer roteres).
 * EntityBlock betyr at blokken har en block entity (et objekt som kan lagre data og tikke).
 */
public class QuarryBlock extends HorizontalDirectionalBlock implements EntityBlock {
	/** true når quarrien får redstone-signal. Da står den på pause. */
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
	/** true når quarrien har mint helt ned til bunnen av verden. Brukes til å vise "ferdig"-fronten. */
	public static final BooleanProperty FINISHED = BooleanProperty.create("finished");
	/** true når quarrien står og venter på fyllblokker. Brukes til å vise den blå fronten. */
	public static final BooleanProperty NEEDS_FILLER = BooleanProperty.create("needs_filler");

	public QuarryBlock(Properties properties) {
		super(properties);
		// Standardtilstanden: fronten vender mot nord, ingen strøm, ikke ferdig og venter ikke på fyllblokker.
		this.registerDefaultState(this.stateDefinition.any()
				.setValue(FACING, Direction.NORTH)
				.setValue(POWERED, false)
				.setValue(FINISHED, false)
				.setValue(NEEDS_FILLER, false));
	}

	// Forteller Minecraft hvilke properties blokken har. Uten denne finnes de ikke på blokken.
	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED, FINISHED, NEEDS_FILLER);
	}

	// Kalles når spilleren plasserer blokken. Spilleren ser i én retning,
	// så vi snur den (getOpposite) slik at fronten vender mot spilleren – akkurat som en ovn.
	// Vi sjekker også om det allerede er redstone-signal der den settes ned.
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		boolean powered = context.getLevel().hasNeighborSignal(context.getClickedPos());
		return this.defaultBlockState()
				.setValue(FACING, context.getHorizontalDirection().getOpposite())
				.setValue(POWERED, powered);
	}

	// Kalles når en naboblokk endrer seg (f.eks. en spak slås på eller av).
	// orientation kan være null; vi bruker den ikke.
	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
			Orientation orientation, boolean movedByPiston) {
		updatePowered(state, level, pos);
	}

	// Kalles når blokken settes i verden (også når den plasseres på andre måter enn av en spiller).
	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		updatePowered(state, level, pos);
	}

	/**
	 * Sjekker om quarrien får redstone-signal fra noen side (direkte eller via en blokk med strøm),
	 * og oppdaterer POWERED bare hvis verdien faktisk har endret seg.
	 */
	private void updatePowered(BlockState state, Level level, BlockPos pos) {
		if (level.isClientSide()) {
			return;
		}
		boolean powered = level.hasNeighborSignal(pos);
		if (powered != state.getValue(POWERED)) {
			// UPDATE_CLIENTS: send den nye staten til spillerne, uten å varsle naboene unødvendig.
			level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
		}
	}

	// Høyreklikk åpner GUI-en. Holder spilleren et item, kalles denne også (useItemOn sender videre hit),
	// bortsett fra når spilleren sniker med et item i hånden – da plasseres blokken som vanlig.
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof QuarryBlockEntity quarry) {
			player.openMenu(quarry);
		}
		return InteractionResult.SUCCESS;
	}

	// Innholdet droppes automatisk av block entityen (BaseContainerBlockEntity) når blokken brytes.
	// Her oppdaterer vi bare naboene etterpå, slik ovnen gjør.
	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		Containers.updateNeighboursAfterDestroy(state, level, pos);
	}

	// Lager en ny block entity når blokken settes ut. Et nytt objekt starter alltid på A1.
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new QuarryBlockEntity(pos, state);
	}

	// Forteller Minecraft hva som skal skje hver tick. Vi tikker bare på serveren
	// (på klienten returnerer vi null, og da tikker den ikke der).
	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide() || type != ModBlockEntities.QUARRY) {
			return null;
		}
		return (BlockEntityTicker<T>) (BlockEntityTicker<QuarryBlockEntity>) QuarryBlockEntity::serverTick;
	}
}
