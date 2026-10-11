package com.seigmannen.quarrymod.client.screen;

import com.seigmannen.quarrymod.QuarryMod;
import com.seigmannen.quarrymod.block.entity.QuarryBlockEntity;
import com.seigmannen.quarrymod.item.ModItems;
import com.seigmannen.quarrymod.menu.QuarryMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * GUI-en til quarrien (bare på klienten). Tegner bakgrunnen, tittel, status og filterknappen.
 */
public class QuarryScreen extends AbstractContainerScreen<QuarryMenu> {
	private static final Identifier TEXTURE = QuarryMod.id("textures/gui/container/quarry.png");
	private static final int TEXTURE_SIZE = 256;
	private static final int LABEL_COLOR = 0xFF404040; // samme grå som vanilla-titler
	// Slot-grå (0x8B8B8B) med ca. 60 % dekkevne (0x99 = 153/255).
	private static final int GHOST_OVERLAY_COLOR = 0x998B8B8B;
	// Status-teksten: sentrert om midten av fuel-slotten (x = 17 + 8), over skillelinjen på y = 94.
	private static final int STATUS_CENTER_X = 25;
	private static final int STATUS_MIN_X = 4;
	private static final int STATUS_LINE_1_Y = 76;
	private static final int STATUS_LINE_2_Y = 86;
	// Filterknappen (90x20) og advarselen sentrert under den.
	private static final int FILTER_BUTTON_X = 8;
	private static final int FILTER_BUTTON_Y = 100;
	private static final int FILTER_BUTTON_WIDTH = 90;
	private static final int WHITELIST_WARNING_Y = 124;
	private static final ItemStack PICKAXE_ICON = new ItemStack(Items.IRON_PICKAXE);
	private static final ItemStack FUEL_ICON = new ItemStack(Items.COAL);
	// ModItems er alltid registrert før klienten åpner en GUI.
	private static final ItemStack SPEED_UPGRADE_ICON = new ItemStack(ModItems.SPEED_UPGRADES[0]);
	private static final ItemStack AREA_UPGRADE_ICON = new ItemStack(ModItems.AREA_UPGRADES[0]);

	private Button filterButton;

	public QuarryScreen(QuarryMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 248);
		this.titleLabelX = 8;
		this.titleLabelY = 6;
	}

	@Override
	protected void init() {
		super.init();
		// Knappen sender knapp-ID-en til serveren (clickMenuButton i menyen), som bytter modus.
		this.filterButton = this.addRenderableWidget(Button.builder(filterText(), button -> {
			if (this.minecraft.gameMode != null) {
				this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, QuarryMenu.BUTTON_CYCLE_FILTER);
			}
		}).bounds(this.leftPos + FILTER_BUTTON_X, this.topPos + FILTER_BUTTON_Y, FILTER_BUTTON_WIDTH, 20).build());
	}

	// Oppdater knappeteksten når serveren har sendt ny modus.
	@Override
	protected void containerTick() {
		super.containerTick();
		this.filterButton.setMessage(filterText());
	}

	private Component filterText() {
		return switch (this.menu.getFilterMode()) {
			case QuarryBlockEntity.FILTER_WHITELIST -> Component.translatable("gui.quarrymod.filter.whitelist").withStyle(ChatFormatting.GREEN);
			case QuarryBlockEntity.FILTER_BLACKLIST -> Component.translatable("gui.quarrymod.filter.blacklist").withStyle(ChatFormatting.RED);
			default -> Component.translatable("gui.quarrymod.filter.disabled").withStyle(ChatFormatting.GRAY);
		};
	}

	/** Status under fuel-slotten: "Ready"/"Empty" på én linje, eller antallet og "blocks" på to linjer. */
	private void drawStatus(GuiGraphicsExtractor graphics) {
		switch (this.menu.getStatus()) {
			case QuarryBlockEntity.STATUS_READY ->
					drawStatusLine(graphics, Component.translatable("gui.quarrymod.status.ready").withStyle(ChatFormatting.YELLOW), STATUS_LINE_1_Y);
			case QuarryBlockEntity.STATUS_RUNNING -> {
				// Blokkene den innlastede fuelen rekker. Oppdateres hver gang serveren sender nytt tall.
				drawStatusLine(graphics, Component.literal(Integer.toString(this.menu.getRemainingBlocks())).withStyle(ChatFormatting.GREEN), STATUS_LINE_1_Y);
				drawStatusLine(graphics, Component.translatable("gui.quarrymod.status.blocks").withStyle(ChatFormatting.GREEN), STATUS_LINE_2_Y);
			}
			case QuarryBlockEntity.STATUS_NO_FILLER -> {
				// Venter på fyllblokker. Linje 2 kan være tom (f.eks. "No filler" på engelsk).
				drawStatusLine(graphics, Component.translatable("gui.quarrymod.status.no_filler_1").withStyle(ChatFormatting.YELLOW), STATUS_LINE_1_Y);
				Component line2 = Component.translatable("gui.quarrymod.status.no_filler_2").withStyle(ChatFormatting.YELLOW);
				if (!line2.getString().isEmpty()) {
					drawStatusLine(graphics, line2, STATUS_LINE_2_Y);
				}
			}
			default ->
					drawStatusLine(graphics, Component.translatable("gui.quarrymod.status.empty").withStyle(ChatFormatting.RED), STATUS_LINE_1_Y);
		}
	}

	/**
	 * Sentrert under fuel-slotten. Med skygge, så gult/grønt er lett å lese på den grå bakgrunnen.
	 * Lange ord (f.eks. "fyllblokker") skyves inn slik at de ikke går utenfor kanten av GUI-en.
	 */
	private void drawStatusLine(GuiGraphicsExtractor graphics, Component text, int y) {
		int x = Math.max(STATUS_MIN_X, STATUS_CENTER_X - this.font.width(text) / 2);
		graphics.text(this.font, text, x, y, 0xFFFFFFFF, true);
	}

	// Bakgrunnsteksturen: 176x248 øverst til venstre i en 256x256-fil.
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, this.leftPos, this.topPos, 0.0F, 0.0F,
				this.imageWidth, this.imageHeight, TEXTURE_SIZE, TEXTURE_SIZE);

		// Falmede ikoner i tomme slots. Tegnes i bakgrunnen, så ekte items og hover-markering havner over.
		Slot pickaxeSlot = this.menu.getSlot(QuarryMenu.PICKAXE_SLOT);
		Slot fuelSlot = this.menu.getSlot(QuarryMenu.FUEL_SLOT);
		Slot speedSlot = this.menu.getSlot(QuarryMenu.SPEED_UPGRADE_SLOT);
		Slot areaSlot = this.menu.getSlot(QuarryMenu.AREA_UPGRADE_SLOT);
		drawGhostItem(graphics, pickaxeSlot, PICKAXE_ICON);
		drawGhostItem(graphics, fuelSlot, FUEL_ICON);
		drawGhostItem(graphics, speedSlot, SPEED_UPGRADE_ICON);
		drawGhostItem(graphics, areaSlot, AREA_UPGRADE_ICON);
		// Items tegnes etter fills i samme lag, så vi går til et nytt lag før det grå laget legges over.
		graphics.nextStratum();
		drawGhostOverlay(graphics, pickaxeSlot);
		drawGhostOverlay(graphics, fuelSlot);
		drawGhostOverlay(graphics, speedSlot);
		drawGhostOverlay(graphics, areaSlot);
	}

	// Tooltip over tomme upgrade-slots ("Speed Upgrade" / "Area Upgrade"), bare når man ikke holder noe.
	@Override
	protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractTooltip(graphics, mouseX, mouseY);
		if (this.hoveredSlot == null || this.hoveredSlot.hasItem() || !this.menu.getCarried().isEmpty()) {
			return;
		}
		if (this.hoveredSlot.index == QuarryMenu.SPEED_UPGRADE_SLOT) {
			graphics.setTooltipForNextFrame(this.font, Component.translatable("gui.quarrymod.slot.speed_upgrade"), mouseX, mouseY);
		} else if (this.hoveredSlot.index == QuarryMenu.AREA_UPGRADE_SLOT) {
			graphics.setTooltipForNextFrame(this.font, Component.translatable("gui.quarrymod.slot.area_upgrade"), mouseX, mouseY);
		}
	}

	private void drawGhostItem(GuiGraphicsExtractor graphics, Slot slot, ItemStack icon) {
		if (!slot.hasItem()) {
			graphics.fakeItem(icon, this.leftPos + slot.x, this.topPos + slot.y);
		}
	}

	/** Halvgjennomsiktig slot-grått lag over ikonet, så det ser falmet ut. */
	private void drawGhostOverlay(GuiGraphicsExtractor graphics, Slot slot) {
		if (!slot.hasItem()) {
			int x = this.leftPos + slot.x;
			int y = this.topPos + slot.y;
			graphics.fill(x, y, x + 16, y + 16, GHOST_OVERLAY_COLOR);
		}
	}

	// Tekst relativt til GUI-ens hjørne. Vi tegner ikke "Inventory"-tittelen.
	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(this.font, this.title, this.titleLabelX, this.titleLabelY, LABEL_COLOR, false);
		drawStatus(graphics);
		// Advarsel sentrert under filterknappen (knappen slutter på y = 120, upgrade-slotsene starter på y = 136).
		if (this.menu.getFilterMode() == QuarryBlockEntity.FILTER_WHITELIST && this.menu.isFilterEmpty()) {
			Component warning = Component.translatable("gui.quarrymod.filter.whitelist_empty").withStyle(ChatFormatting.YELLOW);
			int x = FILTER_BUTTON_X + FILTER_BUTTON_WIDTH / 2 - this.font.width(warning) / 2;
			graphics.text(this.font, warning, x, WHITELIST_WARNING_Y, 0xFFFFFFFF, true);
		}
	}
}
