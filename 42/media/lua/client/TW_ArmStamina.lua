require('ISUI/ISPanel')
local R=require('TW/Runtime')
local options=require('TW_ArmStaminaOptions')
local M={panels={},parts={},errors=0}
TWArmStamina=M
local Bar=ISPanel:derive('TWArmStaminaBar')
local function overlaps(a,b)
    return a.x<b.x+b.w and a.x+a.w>b.x and a.y<b.y+b.h and a.y+a.h>b.y
end
-- All coordinates are screen pixels. Search closest to the hotbar first.
function M.place(anchor,blockers,screen,w,h,gap)
    local candidates={}
    local x=anchor.x+(anchor.w-w)/2
    for i=0,8 do
        candidates[#candidates+1]={x=x,y=anchor.y-gap-h-i*(h+gap),w=w,h=h}
    end
    candidates[#candidates+1]={x=anchor.x-w-gap,y=anchor.y,w=w,h=h}
    candidates[#candidates+1]={x=anchor.x+anchor.w+gap,y=anchor.y,w=w,h=h}
    table.sort(candidates,function(a,b)
        local function distance(r) return (r.x+w/2-anchor.x-anchor.w/2)^2+(r.y+h/2-anchor.y)^2 end
        return distance(a)<distance(b)
    end)
    for _,rect in ipairs(candidates) do
        local free=rect.x>=screen.x and rect.y>=screen.y and rect.x+w<=screen.x+screen.w and rect.y+h<=screen.y+screen.h
        for _,other in ipairs(blockers) do if overlaps(rect,other) then free=false;break end end
        if free then return rect end
    end
end
function Bar:prerender() end
function Bar:render()
    self.draws=(self.draws or 0)+1
    local api=rawget(_G,'ArmStamina')
    local p=getSpecificPlayer(self.playerNum)
    local sample=api and api.snapshot and api.snapshot(p)
    local option=options:getOption('ArmStaminaBar')
    self.shown=false
    if not sample or sample.fraction>=.99999 or option and option:getValue()==false or not p or p:isDead() then return end
    local index=self.playerNum
    local screen={x=getPlayerScreenLeft(index),y=getPlayerScreenTop(index),w=getPlayerScreenWidth(index),h=getPlayerScreenHeight(index)}
    local s=math.max(1,getTextManager():getFontHeight(UIFont.Small)/14)
    local hotbar=getPlayerHotbar and getPlayerHotbar(index)
    local anchor=hotbar and hotbar:getIsVisible() and {x=hotbar:getAbsoluteX(),y=hotbar:getAbsoluteY(),w=hotbar:getWidth(),h=hotbar:getHeight()}
        or {x=screen.x+screen.w*.5-100*s,y=screen.y+screen.h-48*s,w=200*s,h=40*s}
    local blockers={anchor}
    local elements=UIManager.getUI()
    for i=0,elements:size()-1 do
        local el=elements:get(i)
        if el~=self.javaObject and el:getTable()~=self and el:isVisible() then
            local rect={x=el:getX(),y=el:getY(),w=el:getWidth(),h=el:getHeight()}
            -- Full-screen event/input layers have no occupied HUD rectangle.
            if rect.w>0 and rect.h>0 and rect.w*rect.h<screen.w*screen.h*.7 then blockers[#blockers+1]=rect end
        end
    end
    local rect=M.place(anchor,blockers,screen,180*s,10*s,6*s)
    if not rect then return end
    self:setX(rect.x);self:setY(rect.y);self:setWidth(rect.w);self:setHeight(rect.h)
    self:drawRect(0,0,rect.w,rect.h,.8,.04,.05,.05)
    local r,g,b=sample.exhausted and .75 or .43,sample.exhausted and .32 or .66,sample.exhausted and .23 or .72
    self:drawRect(1,1,(rect.w-2)*math.max(0,math.min(1,sample.fraction)),rect.h-2,.95,r,g,b)
    self:drawRectBorder(0,0,rect.w,rect.h,.8,.55,.58,.56)
    self.shown=true;self.sample=sample;self.lastRect=rect
end
function M.update()
    local api=rawget(_G,'ArmStamina')
    if not (api and api.update) then return end
    for i=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(i)
        if p and p:isLocalPlayer() and not p:isDead() then
            local gun=p:getPrimaryHandItem()
            local key=gun and R.key(gun)
            -- The part list of the shared snapshot (R.view), rebuilt only when the parts change; Java reads it at once.
            local tree=key and (R.viewTree or R.tree)(gun)
            local cached=M.parts[i]
            if not cached or cached.tree~=tree then
                local parts={}
                if tree then for path,tpl in pairs(tree) do parts[#parts+1]={path=path,tpl=tpl} end end
                cached={tree=tree,parts=parts};M.parts[i]=cached
            end
            local parts=cached.parts
            local recoil=rawget(_G,'TWFirstPersonRecoil')
            api.update(p,{key=key or '',parts=parts,aiming=p:isAiming()==true,paused=isGamePaused()==true,
                ammo=key and recoil and recoil.ammoTpl(gun) or '',
                rounds=key and gun:getCurrentAmmoCount()+(gun:isRoundChambered() and 1 or 0) or 0,
                injury=not (SandboxVars and SandboxVars.EscapeFromZomboidFirearms and SandboxVars.EscapeFromZomboidFirearms.InjuryStates==false)},
                getGameTime():getRealworldSecondsSinceLastUpdate())
            if not M.panels[i] then
                local panel=Bar:new(0,0,180,10);panel.playerNum=i;panel:initialise();panel:instantiate()
                panel.background=false;panel:setWantMouseEvents(false);panel:addToUIManager();M.panels[i]=panel
            end
        end
    end
end
local function reset()
    for _,panel in pairs(M.panels) do panel:removeFromUIManager() end
    M.panels={};M.parts={}
    local api=rawget(_G,'ArmStamina');if api and api.reset then api.reset() end
end
-- Load order does not give this callback ownership: Java arbitrates the world frame.
Events.OnTick.Add(function()
    local ok,why=pcall(M.update)
    if not ok and M.lastError~=tostring(why) then M.errors=M.errors+1;M.lastError=tostring(why);print('[TW-ARM-STAMINA] '..M.lastError) end
end)
Events.OnGameStart.Add(reset)
Events.OnPlayerDeath.Add(function(p)
    local api=rawget(_G,'ArmStamina');if api and api.off then api.off(p) end
    local index=p and p:getPlayerNum();local panel=index and M.panels[index]
    if panel then panel:removeFromUIManager();M.panels[index]=nil end
end)
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(reset) end
return M
