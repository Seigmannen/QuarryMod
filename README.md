# Quarry Mod

> **⚠️ WORK IN PROGRESS**
> This mod is still under development. Features may change, and things may be unstable or break between versions. Back up your worlds before using it.

A simple quarry for Minecraft 26.3 (Fabric), inspired by Simple Quarries Plus.

## Features

- Mines a 15x15 area centered on the quarry, layer by layer, starting from the layer below it and going down to the bottom of the world
- Mines in a fixed grid order starting at the back-left corner (relative to the front of the quarry)
- Mines one block every 6 ticks (diamond pickaxe speed on stone)
- Skips air, liquids and unbreakable blocks
- Drops as if mined with a diamond pickaxe (e.g. diamond ore gives diamonds)
- Outputs items on top of the quarry, which works well with water collection systems
- Container contents (e.g. chests) drop on the ground like in vanilla
- Remembers its progress when the chunk unloads or the world is reloaded
- Redstone control: a redstone signal pauses it
- Status light on the front: green = running, red = paused, off = finished

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

## Planned features

- [ ] Fuel
- [ ] Use regular pickaxes in the quarry: the pickaxe type and its enchantments affect mining (e.g. Silk Touch mines with silk touch, Fortune gives fortune drops, Efficiency mines faster)
- [ ] Inventory
- [ ] Item filter with three modes: whitelist / blacklist / disabled
- [ ] Upgrades:
  - [ ] Speed upgrades (mine faster)
  - [ ] Area upgrades (larger mining area)

## Requirements

- Minecraft 26.3
- Fabric Loader
- Fabric API

## License

This mod is licensed under the [MIT License](LICENSE).

## Credits

This mod is heavily inspired by [Simple Quarries Plus]([LENKE]). All code and textures in this mod were made from scratch.
