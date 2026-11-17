require('TimedActions/ISBaseTimedAction')
local R=require('TW/Runtime')
ISTWAssemblyAction=ISBaseTimedAction:derive('ISTWAssemblyAction')
-- Steps of a planned build name a template instead of an item: the part is
-- picked from the inventory or a bag in it when the step starts, because
-- earlier steps may return it.
function ISTWAssemblyAction:resolvePart()
    if self.tpl and (not self.part or not R.inInventory(self.character,self.part)) then
        self.part=R.findLoose(self.character,self.tpl)
    end
    return self.part
end
function ISTWAssemblyAction:isValid()
    -- Multiplayer (B42): the server checks the step again and completes it (complete), and its
    -- result reaches this client before the action ends - the part leaves the inventory, the
    -- weapon's parts change - so the rules turned false here: the client stopped the action, which
    -- skips perform and resets the whole queue (a build ended after its first step). Once started,
    -- the client keeps only the weapon in the player's inventory (main or a bag), as vanilla
    -- ISRemoveWeaponUpgrade does.
    if isClient() and self.twStarted then return R.inInventoryID(self.character,self.weapon:getID()) end
    if self.mode=='install' or self.mode=='replace' then self:resolvePart() end
    if self.mode=='install' then return R.canInstall(self.character,self.weapon,self.part,self.path) end
    if self.mode=='replace' then return R.canReplace(self.character,self.weapon,self.part,self.path) end
    return R.canRemove(self.character,self.weapon,self.path,self.mode=='removeBranch')
end
function ISTWAssemblyAction:start() self.twStarted=true end
function ISTWAssemblyAction:update() self.character:setMetabolicTarget(Metabolics.LightDomestic) end
function ISTWAssemblyAction:stop() ISBaseTimedAction.stop(self) end
-- Tarkov inventory sound of a weapon mod (TW_Sounds): attached or taken off.
function ISTWAssemblyAction:perform()
    if self.character.playSound then self.character:playSound((self.mode=='install' or self.mode=='replace') and 'TW_PartAttach' or 'TW_PartDetach') end
    ISBaseTimedAction.perform(self)
end
function ISTWAssemblyAction:complete()
    if not self:isValid() then return false end
    if self.mode=='install' then return R.install(self.character,self.weapon,self.part,self.path) end
    if self.mode=='replace' then return R.replace(self.character,self.weapon,self.part,self.path) end
    return R.remove(self.character,self.weapon,self.path,self.mode=='removeBranch')
end
function ISTWAssemblyAction:getDuration()
    if self.character:isTimedActionInstant() then return 1 end
    -- A whole branch takes the time of each removed part.
    if self.mode=='removeBranch' then
        local tree=R.tree(self.weapon)
        local count=1
        for path in pairs(tree) do if path:sub(1,#self.path+1)==self.path..'/' then count=count+1 end end
        return 60*math.min(count,8)
    end
    return 60
end
function ISTWAssemblyAction:new(character,weapon,mode,path,part,tpl)
    local o=ISBaseTimedAction.new(self,character)
    o.weapon,o.mode,o.path,o.part,o.tpl=weapon,mode,path,part,tpl
    o.maxTime=o:getDuration()
    return o
end
