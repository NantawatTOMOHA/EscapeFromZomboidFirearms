-- Shared registration lets dedicated servers simulate grenades without the client Java bridge.
local M=require('TW/GrenadeFlight')
Events.OnGameBoot.Add(M.install)
Events.OnGameStart.Add(function() M.reset();M.install() end)
Events.OnClientCommand.Add(function(module,command,player,args)
    local ok,why=pcall(M.receive,module,command,player,args)
    if not ok then M.fail('server command: '..tostring(why)) end
end)
Events.OnTick.Add(M.tick)
