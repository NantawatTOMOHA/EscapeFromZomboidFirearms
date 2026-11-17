-- Viewpoint's relief under a projectile's flight (W10 item 14, 27.10.2026: launcher rounds and hand grenades flew
-- PZ's flat world while Viewpoint drew the ground and every model lifted by FarRelief$Lift, so a far round landed
-- beyond or short of the drawn aim point). The shooter's client samples the drawn lift along the launch heading
-- (GrenadeFlightPose.reliefTable, ShotProtocol.Relief's sampler); the flight's owner moves the round on its own
-- source arc in the drawn world and traces each point at its drawn height less the lift there, relative to the
-- launch point - the bullets' ShotPenetration.Warp contract. Lift differences do not depend on the camera
-- (Lift.at = (field - ground under the camera) x height), so one profile holds for the whole flight.
-- Profile on the wire: {s={mm...}, l={mm...}}: horizontal distances along the heading (strictly rising integers,
-- steps <= 65535, <= 400 m) and lifts (integer millimetres, |l| <= 32767), linear between samples, 0 behind the
-- launch point, the last lift beyond the end. A server cannot check a relief: like ShotServer it bounds every lift to
-- +-min(32 m, 0.5 m + 0.35 x distance) (RELIEF_BASE_METRES, RELIEF_GRADE, RELIEF_MAX_METRES).
local F={BASE=.5,GRADE=.35,MAX=32,MAX_SAMPLES=512,MAX_RANGE=400000,MAX_STEP=65535}
local function integer(v) return type(v)=='number' and v==v and v%1==0 end

-- A validated, clamped profile from launch point (ox, oy) along heading (hx, hy) (any length), PZ cells to metres
-- by metresPerCell; nil and a reason for a missing, malformed or near-vertical one. clamped counts bounded lifts.
function F.read(t,ox,oy,hx,hy,metresPerCell)
    if t==nil then return nil,'none' end
    if type(t)~='table' or type(t.s)~='table' or type(t.l)~='table' then return nil,'shape' end
    for _,v in ipairs({ox,oy,hx,hy,metresPerCell}) do
        if type(v)~='number' or v~=v or v==math.huge or v==-math.huge then return nil,'origin' end
    end
    local h=math.sqrt(hx*hx+hy*hy)
    if h<1e-3 or metresPerCell<=0 then return nil,'heading' end
    local n=#t.s
    if n<1 or n>F.MAX_SAMPLES or #t.l~=n then return nil,'count' end
    local s,l,previous,clamped={},{},0,0
    for i=1,n do
        local d,v=t.s[i],t.l[i]
        if not integer(d) or not integer(v) or d<=previous or d-previous>F.MAX_STEP or d>F.MAX_RANGE
            or v<-32767 or v>32767 then return nil,'sample' end
        local bound=math.floor(math.min(F.MAX,F.BASE+F.GRADE*d/1000)*1000)
        if v>bound then v=bound;clamped=clamped+1 elseif v<-bound then v=-bound;clamped=clamped+1 end
        s[i]=d;l[i]=v;previous=d
    end
    return {ox=ox,oy=oy,hx=hx/h,hy=hy/h,mpc=metresPerCell,s=s,l=l,n=n,clamped=clamped,cursor=1}
end

-- The profile's lift (metres, relative to the launch point) at PZ point (x, y): by its distance along the heading.
function F.lift(p,x,y)
    local d=((x-p.ox)*p.hx+(y-p.oy)*p.hy)*p.mpc*1000
    if not (d>0) then return 0 end
    local s,n=p.s,p.n
    if d>=s[n] then return p.l[n]/1000 end
    -- Flights move away from the launch point: start from the last sample used.
    local i=p.cursor
    if s[i]<d then repeat i=i+1 until s[i]>=d
    else while i>1 and s[i-1]>=d do i=i-1 end end
    p.cursor=i
    local a=i==1 and 0 or s[i-1]
    local la=i==1 and 0 or p.l[i-1]
    return (la+(p.l[i]-la)*(d-a)/(s[i]-a))/1000
end

-- The wire form of a profile (its clamped values), for the observers' copies of the same flight.
function F.wire(p)
    local s,l={},{}
    for i=1,p.n do s[i]=p.s[i];l[i]=p.l[i] end
    return {s=s,l=l}
end

-- Log text: samples, range, bounded lifts and the lift at 10-100 m.
function F.describe(p)
    if not p then return 'relief=none' end
    local parts={}
    for _,m in ipairs({10,25,50,75,100}) do
        local x,y=p.ox+p.hx*m/p.mpc,p.oy+p.hy*m/p.mpc
        parts[#parts+1]=string.format('%d:%.3f',m,F.lift(p,x,y))
    end
    p.cursor=1
    return string.format('relief={samples=%d range=%.1f clamped=%d lift=%s}',p.n,p.s[p.n]/1000,p.clamped,table.concat(parts,','))
end
return F
