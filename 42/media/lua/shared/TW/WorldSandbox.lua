-- The world's own sandbox options for the passes that run on the distribution merge: the mod's loot
-- tables (TW_Distributions) and the zombie weapon lists (TW_LootZombies). B42.21 IsoWorld.init fires
-- OnPreDistributionMerge..OnPostDistributionMerge before SandboxOptions.load() reads the save's
-- map_sand.bin (bytecode 2051-2126, not on a client), so a continued game built both from the main
-- menu's SandboxVars (found in game 21.10.2026: the zombies of a continued world carried weapons by the
-- default NATO / Warsaw Pact sliders, not the world's). This is the engine's own call one step early: it
-- reads the save's file and writes SandboxVars (toLua); without the file (a new game) it resets the
-- Java options and reads them back from SandboxVars, so the sandbox screen's values stay.
local M={loads=0}

function M.load()
    if isClient() or type(getSandboxOptions)~='function' then return false end
    local options=getSandboxOptions()
    if not options then return false end
    local ok,err=pcall(function() options:load() end)
    if ok then
        M.loads=M.loads+1
        -- W11 range: the world's own sandbox options now reach the item scripts before the loot pass creates
        -- any weapon, and the mod's runtime layer reads the same values (TW/RangeOptions, TW/Core.pzStats).
        pcall(function()
            local range=require('TW/RangeOptions')
            range.refresh(true)
            range.start()
        end)
    else
        print('[TW] world sandbox options not read before the loot pass: '..tostring(err))
    end
    return ok
end

return M
