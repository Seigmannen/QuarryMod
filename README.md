# Quarry Mod

> **⚠️ BETA**
> This is a beta release. Expect bugs, and back up your worlds before using it.

A simple quarry for Minecraft 26.3 (Fabric), inspired by Simple Quarries Plus.

## Features

- Mines an area centered on the quarry (15x15 by default, larger with area upgrades), layer by layer, starting from the layer below it and going down to the layer just above the bottom of the world (the bottom layer is optional, see below)
- Mines in a fixed grid order starting at the back-left corner (relative to the front of the quarry)
- Mining speed depends on the inserted pickaxe (10x slower than a player mining stone, e.g. about 3 seconds per block with a diamond pickaxe). All blocks take the same time.
- Skips air and unbreakable blocks (except bedrock and reinforced deepslate with Mine (Almost) Everything, see Compatibility)
- Mine bottom layer toggle (button with a bedrock icon in the GUI, off by default): lets the quarry also mine the lowest layer of the world. In the Overworld and the Nether this layer is bedrock, so the toggle only has an effect there when Mine (Almost) Everything is installed.
- Fills water and lava with filler blocks from storage (cobblestone, stone, deepslate, dirt, netherrack and similar). The player can add filler blocks to the storage.
- Builds a wall around the area: the ring just outside the mining area is filled too and never mined, so lakes and oceans stay out
- Stops with a blue light when it runs out of filler blocks, and continues automatically when more are added
- A hopper below always leaves at least 64 filler blocks in storage
- Mined items go into internal storage; when storage is full, items are ejected on top (works well with water collection systems)
- Container contents (e.g. chests) drop on the ground like in vanilla
- Remembers its progress when the chunk unloads or the world is reloaded
- Redstone control: a redstone signal pauses it
- Status light on the front: green = running, red = paused, blue = needs filler blocks, off = finished
- Needs both a pickaxe and fuel to work
- Uses the pickaxe you insert: tool speed, Efficiency, Silk Touch, Fortune and Unbreaking work like vanilla, and the pickaxe takes durability damage
- Runs on furnace fuel: one smelt in a furnace = 12 blocks (coal = 96, lava bucket = 1200); empty buckets go to storage
- 24-slot internal storage; overflow is ejected on top
- Hopper support: pickaxes from the back, fuel from the sides, storage can be pulled from below
- Item filter (whitelist / blacklist / disabled) that works on the drops; filtered-out items are destroyed. Silk Touch drops are filtered as what they actually drop (e.g. stone, not cobblestone).
- Speed upgrades I–V: reduce the slowdown factor step by step (10 → 7 → 5 → 3 → 2 → 1). Speed V also gives a Haste II effect, so Efficiency V on diamond, netherite or gold mines instantly. Fuel use does not change: the same amount of fuel per block mined.
- Area upgrades I–V: 25x25, 35x35, 45x45, 55x55 and 65x65. A new size takes effect from the next layer.
- Upgrade recipes: the previous tier in the center with 4 materials around it in a plus shape (iron, gold, emerald, diamond, netherite). Tier I uses a redstone block (speed) or an ender pearl (area) in the center.

## Crafting

```
R I R
P D P
R I R
```

- **R** = Redstone Dust
- **I** = Block of Iron
- **P** = Diamond Pickaxe
- **D** = Dropper

### Upgrades

```
. X .
X U X
. X .
```

- **.** = empty
- **X** = material
- **U** = center item

| Tier | U (center)                                  | X (material)    |
|------|---------------------------------------------|-----------------|
| I    | Redstone Block (speed) / Ender Pearl (area) | Iron Ingot      |
| II   | Tier I upgrade of the same type             | Gold Ingot      |
| III  | Tier II upgrade                             | Emerald         |
| IV   | Tier III upgrade                            | Diamond         |
| V    | Tier IV upgrade                             | Netherite Ingot |

## Planned features

- [x] Fuel
- [x] Use regular pickaxes in the quarry: the pickaxe type and its enchantments affect mining (e.g. Silk Touch mines with silk touch, Fortune gives fortune drops, Efficiency mines faster)
- [x] Inventory
- [x] Item filter with three modes: whitelist / blacklist / disabled
- [x] Upgrades:
  - [x] Speed upgrades (mine faster)
  - [x] Area upgrades (larger mining area)
- [x] Liquid handling: before each layer, the quarry scans the layer and the ring just outside the mining area. Water and lava are replaced with filler blocks from its storage (tag `quarrymod:filler_blocks`: cobblestone, cobbled deepslate, stone, deepslate, dirt, netherrack, granite, diorite, andesite, tuff), and waterlogged blocks are drained. The outer ring is never mined, so it forms a wall against lakes and oceans. Players can put filler blocks into storage. Each fill takes as long as mining one block (no fuel cost, no pickaxe wear). Blocks that always contain water (kelp, seagrass, bubble columns, also from other mods) are replaced without drops. If it runs out of filler blocks, it stops and waits (blue front, "No filler" in the GUI) until more are added. A hopper below always leaves at least 64 filler blocks in storage.

## Compatibility

- **Mine (Almost) Everything:** with this mod installed, the quarry can mine bedrock and reinforced deepslate with a netherite pickaxe, and budding amethyst and spawners (which keep their mob type) with Silk Touch on an iron pickaxe or better. Without it, the quarry works as before.
- **Bottom layer:** by default, the quarry does not mine the bottom layer of the world. It can be turned on per quarry with the "Mine bottom layer" button (bedrock icon) in the quarry GUI. Careful: this can open a hole into the void.

## Requirements

- Minecraft 26.3
- Fabric Loader
- Fabric API

## License

This mod is licensed under the [MIT License](LICENSE).

## Credits

This mod is heavily inspired by [Simple Quarries Plus](https://modrinth.com/mod/simple-quarries-plus). All code and textures in this mod were made from scratch.
