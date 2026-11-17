-- Client presentation only. Version checks, Java capability checks, network authority and console
-- diagnostics keep running when these notices are hidden. No Java dependency: a missing Java part is
-- one of the states this setting must work in.
require 'PZAPI/ModOptions'
require('TW_Tactical')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('ShowWarnings',getText('UI_TW_ShowWarnings'),true,getText('UI_TW_ShowWarnings_tooltip'))

local M={loaded=false}
TWWarningOptions=M

local function option() return options:getOption('ShowWarnings') end

-- Vanilla loads ModOptions.ini only when the Mods tab opens. Restore this one value earlier so a
-- hidden startup notice stays hidden after a restart, before any notice is scheduled. Only our own
-- pages and valid booleans count; malformed data keeps the default warning visible.
function M.loadSaved()
    if M.loaded then return end
    M.loaded=true
    pcall(function()
        local file=getFileReader('ModOptions.ini',false)
        if not file then return end
        local saved
        while true do
            local line=file:readLine()
            if line==nil then break end
            local kind,page,id,value=string.match(line,'^([^|]*)|([^|]*)|([^|]*)|(.*)$')
            if page and (page=='EscapeFromZomboidFirearms' or page=='EscapeFromZomboidFirearmsCompat')
                and id=='ShowWarnings' and (value=='true' or value=='false') then saved=value=='true' end
        end
        file:close()
        if saved~=nil then option():setValue(saved) end
    end)
end

function M.enabled()
    M.loadSaved()
    return option():getValue()~=false
end

-- Applying Off also removes an already visible notice. Re-enabling permits the next notice/check;
-- it does not repeat a server request or change a notice's once-per-session delivery policy.
function M.apply()
    if M.enabled() then return end
    local java=rawget(_G,'TWJavaNotice')
    if java and java.dismiss then java.dismiss() end
    local version=rawget(_G,'TWVersionCheck')
    if version and version.dismiss then version.dismiss() end
    local server=rawget(_G,'TWShotOwnerNet')
    if server and server.dismissNotice then server.dismissNotice('warnings-disabled') end
end

Events.OnGameBoot.Add(M.loadSaved)
Events.OnMainMenuEnter.Add(M.apply)
Events.OnFETick.Add(M.apply)
Events.OnTickEvenPaused.Add(M.apply)

return M
