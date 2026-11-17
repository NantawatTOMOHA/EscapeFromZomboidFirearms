-- Optical approximations in PZ floor coordinates, adapted from the Brita B42
-- port (same author). The emitter is the vanilla emitter point of the Tarkov
-- device on its actual mount, in the weapon model space (x lateral, y forward,
-- z up). The hand-held weapon frame is the animated prop bone of the frame
-- (Java part, TWZB.weaponFrame: ModelInstance.getAttachmentWorldPosition of
-- the weapon model). Without it (no ZombieBuddy, no ready model) it comes from
-- measured pose tables (TW/LaserPose, built by tools/build_laser_pose.py):
-- while aiming by vertical aim angle and upper-body twist, without aiming the
-- low-ready hold.
local C=require('TW/Core')
local Pose=require('TW/LaserPose')
local P={verticalScale=2.44949,glassTransmission=.91,windowBottom=.27,windowTop=.86}
-- Beyond +-25 degrees the legs are stepping into a turn and the pose is transient.
P.twist=25
-- Low-ready vertical angle used until an idle frame is measured for a pose class.
P.idlePitch=-35
-- Without aiming B42 holds a rifle across the chest, the bore up and to the
-- side; the beam along it is kept short instead of a line across the screen.
P.idleRange=8

function P.range(player,weapon,beam)
    -- HandWeapon includes attached parts in MaxRange; the character overload
    -- adds the Aiming perk. The Tarkov beam length remains the upper bound.
    local range=weapon:getMaxRange(player)*weapon:getRangeMod(player)
    if range~=range or range==math.huge then return 0 end
    range=math.max(0,range)
    if beam and beam.maxDistance then range=math.min(range,beam.maxDistance) end
    return range
end

-- Animation set of the hand-held weapon, as WeaponType.getWeaponType.
function P.poseClass(player,weapon)
    local cls=player.getVariableString and player:getVariableString('Weapon')
    if Pose.aim[cls] then return cls end
    return weapon and weapon:isTwoHandWeapon() and 'firearm' or 'handgun'
end

local function interpolate(rows,pitch)
    local n=#rows
    if pitch<=rows[1][1] then return rows[1],rows[1],0 end
    if pitch>=rows[n][1] then return rows[n],rows[n],0 end
    local lo,hi=1,n
    while hi-lo>1 do local mid=math.floor((lo+hi)/2);if rows[mid][1]<=pitch then lo=mid else hi=mid end end
    local a,b=rows[lo],rows[hi]
    return a,b,(pitch-a[1])/(b[1]-a[1])
end

-- The animated frame from the Java part; false turns it off (QA compares both).
P.bones=true
function P.boneFrame(player)
    local zb=rawget(_G,'TWZB')
    if not P.bones or type(zb)~='table' or not zb.weaponFrame then return end
    local ok,f=pcall(zb.weaponFrame,player)
    if ok and f and f[12] then return f end
end

-- Devices shine only from the weapon itself (Java TWDeviceGate): an action that draws another
-- model or none in the primary hand (a magazine top-off or unloading) hides the weapon and its
-- lights and lasers; the hand bone then carries a cartridge or nothing (user report 19.10.2026,
-- item 14). Without the Java part: the loose-magazine actions, as the grip IK releases for them.
function P.weaponDrawn(player)
    local gate=rawget(_G,'TWDeviceGate')
    if gate and gate.weaponDrawn then
        local ok,drawn=pcall(gate.weaponDrawn,player)
        if ok then return drawn==true end
    end
    local action=player.getVariableString and player:getVariableString('PerformingAction')
    return action~='InsertBullets' and action~='RemoveBullets'
end

-- Weapon model frame in the look frame: origin and model axes, 12 values.
function P.frame(player,weapon,aiming,pitch)
    local bone=P.boneFrame(player)
    if bone then return bone end
    return P.tableFrame(player,weapon,aiming,pitch)
end

-- The measured frame of the pose tables.
function P.tableFrame(player,weapon,aiming,pitch)
    local cls=P.poseClass(player,weapon)
    local stance=player:isSneaking() and 'sneak' or 'stand'
    if not aiming then
        local idle=Pose.idle[cls] and Pose.idle[cls][stance]
        if idle then return idle end
        pitch=P.idlePitch
    end
    local rows=Pose.aim[cls] and Pose.aim[cls][stance]
    if not rows then return end
    local a,b,t=interpolate(rows,pitch or player:getCurrentVerticalAimAngle())
    local twist=aiming and player.getTwist and player:getTwist() or 0
    if twist~=twist then twist=0 end
    twist=math.max(-P.twist,math.min(P.twist,twist))
    local f={}
    for i=2,13 do
        local base=a[i]+(b[i]-a[i])*t
        local slope=a[i+12]+(b[i+12]-a[i+12])*t
        f[i-1]=base+slope*twist
    end
    return f
end

-- World point of a model-space position on the hand-held weapon and its
-- horizontal reach from the character centre.
function P.place(player,f,m)
    local u=f[1]+f[4]*m[1]+f[7]*m[2]+f[10]*m[3]
    local v=f[2]+f[5]*m[1]+f[8]*m[2]+f[11]*m[3]
    local h=f[3]+f[6]*m[1]+f[9]*m[2]+f[12]*m[3]
    local angle=player:getLookAngleRadians()
    local c,s=math.cos(angle),math.sin(angle)
    return player:getX()+u*c-v*s,player:getY()+u*s+v*c,player:getZ()+h/P.verticalScale,math.sqrt(u*u+v*v)
end

-- World direction of a beam's housing forward axis (or the bore when omitted):
-- unit length in tile units, dz in levels.
function P.axis(player,f,weapon,direction)
    local u,v,h=f[7],f[8],f[9]
    if direction then
        local key=weapon and C.data.byType[weapon:getFullType()]
        local d=C.toRender(key,direction)
        u=f[4]*d[1]+f[7]*d[2]+f[10]*d[3]
        v=f[5]*d[1]+f[8]*d[2]+f[11]*d[3]
        h=f[6]*d[1]+f[9]*d[2]+f[12]*d[3]
    end
    local n=math.sqrt(u*u+v*v+h*h)
    if n<1e-6 then return end
    local angle=player:getLookAngleRadians()
    local c,s=math.cos(angle),math.sin(angle)
    return (u*c-v*s)/n,(u*s+v*c)/n,h/n/P.verticalScale
end

-- Emitter of a beam (root model space, as T.beams): x,y,z, horizontal reach
-- and the frame used. The frame belongs to the exported root model, which is
-- turned about the bore for pistols (C.renderRoll). A given frame f is used as is.
function P.origin(player,weapon,position,aiming,pitch,f)
    f=f or P.frame(player,weapon,aiming,pitch)
    if not f then return end
    local key=weapon and C.data.byType[weapon:getFullType()]
    local x,y,z,reach=P.place(player,f,C.toRender(key,position or {0,.14,.025}))
    return x,y,z,reach,f
end

function P.furnitureHeight(name,properties)
    if properties:has('CustomName') then name=name..' '..tostring(properties:get('CustomName')):lower() end
    if properties:has('GroupName') then name=name..' '..tostring(properties:get('GroupName')):lower() end
    local surface=properties:getSurface()
    if name:find('shelv',1,true) or name:find('locker',1,true) or name:find('wardrobe',1,true) then return .82 end
    if name:find('fridge',1,true) or name:find('refriger',1,true) then return .72 end
    if name:find('counter',1,true) or name:find('desk',1,true) or name:find('table',1,true) then return math.max(.29,math.min(.43,surface/192)) end
    if name:find('seat',1,true) or name:find('bed',1,true) then return .31 end
    if name:find('crate',1,true) or name:find('bin',1,true) or name:find('barrel',1,true) or name:find('trashcontainer',1,true) then return .40 end
    if surface>0 then return math.min(.9,math.max(.18,surface/192)) end
    return .72
end
return P
