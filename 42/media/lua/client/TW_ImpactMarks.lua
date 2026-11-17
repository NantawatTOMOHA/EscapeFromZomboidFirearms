local N=require('TW/ImpactNet')
if Events.OnPostRender then Events.OnPostRender.Add(function() if N.isometric() then N.call('isometric') end end) end
Events.OnTick.Add(function()
    N.call('tick')
    -- Drain even in SP; a later MP connection cannot replay old local contacts.
    for _=1,64 do
        local row=N.call('poll');if type(row)~='table' then break end
        local player=getSpecificPlayer(0)
        if isClient() and player and N.valid(row) then sendClientCommand(player,N.module,'Contact',row) end
    end
end)
Events.OnServerCommand.Add(function(module,command,row)
    if module==N.module and command=='Contact' and N.valid(row) then N.call('receive',row) end
end)
for _,name in ipairs({'OnGameStart','OnMainMenuEnter'}) do
    if Events[name] then Events[name].Add(function() N.call('reset') end) end
end
return N
