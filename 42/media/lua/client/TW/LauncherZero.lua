-- The zero of a standalone launcher's sight mark (W10 launchersight, 27.10.2026; found in item 14: the FN40GL landed
-- about 6 m short of its 25 m mark, the RShG-2 about 5 m beyond it). Tarkov zeroes a sight at its calibration distance
-- with the weapon's default round: an iron sight (mod_align_rear/front) turns the shot itself - the iron branch of
-- ProceduralWeaponAnimation, Weapon.CalculateShotDirectionForIron, launched as Fireport.TransformDirection(
-- LocalShotDirection) - and the mod's bullets zero every channel the same way (ShotOwner solveZero). The Viewpoint launch
-- (TW_GrenadeFlight Client.geometry) left along the drawn bore, so a mark no sight pose zeroes (the FN40GL, MSGL and
-- RShG-2 aim channels, iron sights on a launcher) had no zero at all. M.direction is the launch direction at which the
-- mod's own flight (TW/GrenadeFlight M.flight/M.predict, the model the round flies) from the drawn muzzle comes down
-- through the channel's line of sight (the FpPose camera, TWFirstPersonPose.sightFrame) at the mark's distance from the
-- eye - the rule of the underbarrel marks (tools/launcher_ranges.py eye_zero) - keeping the bore's side angle. A mark a
-- sight pose zeroes (underbarrel quadrants and hinges, the M2A1 axis: the view's rangeMove) keeps the drawn bore.
local GF=require('TW/GrenadeFlight')
local D=GF.data

local M={cache={},size=0,solved=0,failed=0,lastError=nil}
TWLauncherZero=M
local CACHE=64
local LOW,HIGH,STEP=-10,60,2 -- degrees of the launch over the line of sight searched for the zero
local ITERATIONS=48
local PRECISION=1e-5 -- metres of the flight off the line of sight at the mark

local function dot(a,b) return a[1]*b[1]+a[2]*b[2]+a[3]*b[3] end
local function unit(v)
    local n=math.sqrt(dot(v,v))
    if not (n>1e-9) then return nil end
    return {v[1]/n,v[2]/n,v[3]/n}
end

-- Height (metres) of a flight launched `degrees` over a level line of sight from a muzzle `ahead` metres before and
-- `height` metres above the eye, where it is `distance` metres from the eye (linear between the 0.01 s nodes, as the
-- round flies), and whether it comes down there; nil when it never gets that far.
local function heightAt(profile,degrees,ahead,height,distance)
    local r=math.rad(degrees)
    local f=GF.flight(profile,profile.speed*math.cos(r),0,profile.speed*math.sin(r))
    local px,pz
    for i=0,D.cacheNodes+6000 do
        local p=GF.predict(f,i*D.step)
        local x,z=ahead+p[1],height+p[3]
        if px and x>=distance then
            local u=(distance-px)/(x-px)
            return pz+(z-pz)*u,z<pz
        end
        if z<-60 or p[4]<=0 then return nil end
        px,pz=x,z
    end
end
M.heightAt=heightAt

-- The launch angle (degrees over the line of sight) whose flight comes down through the line at `distance`: the lowest
-- one (the direct-fire branch), bracketed upwards in STEP degrees, then halved.
function M.angle(profile,ahead,height,distance)
    local function g(degrees)
        local h=heightAt(profile,degrees,ahead,height,distance)
        return h or -math.huge
    end
    local lo,hi=LOW,nil
    if not (g(lo)<0) then return nil end
    for degrees=LOW+STEP,HIGH,STEP do
        if g(degrees)>0 then hi=degrees;break end
        lo=degrees
    end
    if not hi then return nil end
    local mid
    for _=1,ITERATIONS do
        mid=(lo+hi)/2
        local h=g(mid)
        if math.abs(h)<PRECISION then break end
        if h<0 then lo=mid else hi=mid end
    end
    local _,down=heightAt(profile,mid,ahead,height,distance)
    if not down then return nil end
    return mid
end

-- The line of sight of a view in the launcher's render space: eye, ahead, up and left (FpPose camera axes).
local function sight(key,tree,view)
    local FP=rawget(_G,'TWFirstPersonPose')
    local k=FP and FP.sightFrame and FP.sightFrame(key,tree,view)
    if not k then return nil end
    return {k[4],k[8],k[12]},{-k[3],-k[7],-k[11]},{k[2],k[6],k[10]},{k[1],k[5],k[9]}
end
M.sight=sight

-- The zeroed launch direction (render space, unit) or nil to keep the bore: a root launcher's channel whose mark no
-- sight pose zeroes. muzzle and bore are Client.geometry's (render space); signature names the assembly.
function M.direction(key,tree,view,muzzle,bore,profile,signature)
    if not (view and view.zeroDistance and view.zeroDistance>0 and not view.rangeMove and view.kind~='launcher' and profile) then return nil end
    local id=tostring(signature)..'|'..tostring(view.tpl)..'@'..tostring(view.path)..':'..tostring(view.channel)..':'
        ..tostring(view.mode)..'|'..tostring(view.zeroDistance)..'|'..tostring(view.revision)..'|'..tostring(profile.tpl)
    local hit=M.cache[id]
    if hit~=nil then return hit or nil end
    local ok,result=pcall(function()
        local eye,ahead,up,left=sight(key,tree,view)
        local b=unit(bore)
        if not eye or not b then return false end
        local metres=1.5*D.metresPerCell -- Model.vectorToWorldCoords: 1.5 cells per model unit
        local offset={muzzle[1]-eye[1],muzzle[2]-eye[2],muzzle[3]-eye[3]}
        local degrees=M.angle(profile,dot(offset,ahead)*metres,dot(offset,up)*metres,view.zeroDistance)
        if not degrees then return false end
        local side=dot(b,left)
        local plane=math.sqrt(math.max(0,1-side*side))
        local r=math.rad(degrees)
        local c,s=plane*math.cos(r),plane*math.sin(r)
        return unit({ahead[1]*c+up[1]*s+left[1]*side,ahead[2]*c+up[2]*s+left[2]*side,ahead[3]*c+up[3]*s+left[3]*side}) or false
    end)
    if not ok then M.lastError=tostring(result);result=false end
    if result then M.solved=M.solved+1 else M.failed=M.failed+1 end
    if M.size>=CACHE then M.cache={};M.size=0 end
    M.cache[id]=result;M.size=M.size+1
    return result or nil
end

return M
