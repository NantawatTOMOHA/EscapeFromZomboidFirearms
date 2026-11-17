-- Local owner only. Original device modes, measurement cadence and LCD glyphs.
local C=require('TW/Core')
local F=require('TW/Rangefinder')
local Ray=require('TW/LaserRay')
local Laser=require('TW/LaserRender')
local P=require('TW/LaserGeometry')
local M={views={},samples=0,errors=0,draws=0}
TWRangefinder=M

function M.update(player)
    if not player or not player:isLocalPlayer() then return end
    local n=player:getPlayerNum()
    local previous=M.views[n]
    M.views[n]=nil
    if player:isDead() then return end
    local weapon=player:getPrimaryHandItem()
    local R=TWRuntime
    local key=R and R.visualKey(weapon)
    if not key then return end
    local tree,_,parts=(R.view or R.read)(weapon)
    local devices=F.devices(key,tree,parts)
    if #devices==0 then return end
    local now=getTimestampMs()
    local state,zb=Laser.vpState()
    local compat=rawget(_G,'TWViewpoint')
    if state==0 and type(compat)=='table' and type(compat.isoBlocked)=='function' then
        local ok,blocked=pcall(compat.isoBlocked)
        if ok and blocked then return end
    end
    local frame=P.boneFrame(player)
    local rows,display,panel={},{},{}
    local displayColor
    for _,d in ipairs(devices) do
        local cached=previous and previous.weapon==weapon and previous.rows[d.path]
        if not cached or cached.part~=d.part or cached.mode~=d.modeIndex or now<cached.at or now-cached.at>=d.mode.refresh then
            local aim=player:isAiming() and Ray.aimState(player,weapon,d) or {aiming=false}
            local ray=state>0 and Laser.vpRay(player,weapon,d,frame,nil,d.mode.range)
                or Ray.forBeam(player,weapon,d,aim,d.mode.range)
            local result=ray and Ray.trace(ray,player)
            local reading=F.measure(result,d.mode)
            cached={part=d.part,mode=d.modeIndex,at=now,reading=reading,ray=ray}
            M.samples=M.samples+1
        end
        cached.device=d
        rows[d.path]=cached
        -- Viewpoint first (1) and third person (2): the device's display on the drawn model (FB2-M)
        if (state==1 or state==2) and zb and zb.rangefinderDisplay then
            -- Geometry is rebuilt from the current assembly; the Java render
            -- thread places it using the captured root of this exact VP frame.
            for _,value in ipairs(F.vertices(key,d,cached.reading.text)) do display[#display+1]=value end
            for _,value in ipairs(F.panel(key,d) or {}) do panel[#panel+1]=value end
            displayColor=d.mode.color
        end
    end
    if #display>0 then
        -- The mode's own display panel first: the digits are drawn over it, not over the off panel.
        if #panel>0 and zb.rangefinderPanel then zb.rangefinderPanel(player,weapon,panel,F.data.panelAtlas) end
        zb.rangefinderDisplay(player,weapon,display,displayColor,F.data.atlas)
    end
    M.views[n]={weapon=weapon,rows=rows,vp=state}
end

function M.draw()
    local player=getPlayer()
    local view=player and M.views[player:getPlayerNum()]
    if not view or view.vp~=0 or player:getPrimaryHandItem()~=view.weapon then return end
    local y=getPlayerScreenTop(player:getPlayerNum())+getPlayerScreenHeight(player:getPlayerNum())-130
    for _,row in pairs(view.rows) do
        local text=row.reading.text..(row.reading.metres and ' m' or '')
        local width=getTextManager():MeasureStringX(UIFont.Small,text)
        local x=getPlayerScreenLeft(player:getPlayerNum())+getPlayerScreenWidth(player:getPlayerNum())*.5-width*.5
        getTextManager():DrawString(UIFont.Small,x,y,text,.8,.9,.55,1)
        y=y-20;M.draws=M.draws+1
    end
end

local function safeUpdate(player)
    local ok,err=pcall(M.update,player)
    if not ok then
        local n=player and player:getPlayerNum()
        if n then M.views[n]=nil end
        M.errors=M.errors+1
        if M.lastError~=tostring(err) then M.lastError=tostring(err);print('[TW] rangefinder '..M.lastError) end
    end
end
Events.OnPlayerUpdate.Add(safeUpdate)
-- The UI pass: text drawn from OnRenderTick never reached the isometric screen.
Events.OnPostUIDraw.Add(M.draw)
Events.OnGameStart.Add(function() M.views={} end)
return M
