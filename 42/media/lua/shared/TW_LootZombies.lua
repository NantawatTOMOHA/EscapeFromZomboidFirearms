-- The mod weapons on zombies (plan 0.9.x D9, D10): twins of the vanilla attached weapon
-- definitions with firearms and the vanilla filter (TW/LootZombie), written into
-- AttachedWeaponDefinitions before the engine reads it. Java reads the table once, at the first
-- zombie that rolls an attached weapon (AttachedWeaponDefinitions.checkDirty), which comes after
-- the world's sandbox options are loaded; the pass runs with the loot tables on
-- OnPostDistributionMerge, where the options are known (checked in game, 0.9.0).
--
-- Both sides, explicitly. In a multiplayer client process the merge runs before GameClient.client
-- is set, so the table already came out with the twins (W6 mp_loot, 24.10.2026: a joining client
-- logged 36 twins and the server's own weapons on its zombies) - but that is an accident of the
-- join order: a world built while the client flag is already set (a reconnect or a world rebuilt
-- in place) would keep the vanilla definitions and roll a vanilla pistol or shotgun for the zombie
-- a player sees. The tables and the container fill stay server work (the engine rolls and fills on
-- the server); this data table is what each side rolls its own zombies from, so it is the mod's on
-- every side.
local Loot=require('TW/Loot')
local Z=require('TW/LootZombie')

local TWLootZombies={report=nil}
TWLootZombiesAPI=TWLootZombies

function TWLootZombies.apply(o)
    if TWLootZombies.report or not AttachedWeaponDefinitions then return TWLootZombies.report end
    -- A continued game: the save's options first (TW/WorldSandbox), not the main menu's.
    if not o then require('TW/WorldSandbox').load() end
    o=o or Loot.options()
    local plan=Z.plan(AttachedWeaponDefinitions,o)
    local report=Z.apply(AttachedWeaponDefinitions,plan)
    TWLootZombies.report=report
    TWLootZombies.plan=plan
    print(string.format('[TW] loot zombies: %d twins, %d custom outfit entries, %d vanilla definitions removed, %d changed, %d vanilla firearm parts kept apart; ZombieWeapons=%s VanillaFirearms=%s client=%s',
        report.twins,report.inserts,report.removed,report.replaced,report.extra,tostring(o.ZombieWeapons),tostring(o.VanillaFirearms),
        tostring(isClient())))
    return report
end

Events.OnPostDistributionMerge.Add(function()
    TWLootZombies.apply()
end)

return TWLootZombies
