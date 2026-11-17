-- EFT source values describe its native axes, not PZ bone transforms. The PZ
-- pose limits below are proposals, deliberately separate from that evidence.
local D={version=1,
    sampleSeconds=.05,marginMetres=.06,radiusMetres=.015,
    enterMetres=.006,releaseMetres=.025,latchedRatio=.015,
    -- PZ world z is in levels; weaponFrame's height is in isotropic tile units.
    levelHeight=2.44949,
    geometry={edgeHalfWidth=.012,jambWidth=.075,doorTop=.92,
        windowBottom=.31,windowTop=.81,lowTop=.39,tallFenceTop=.73,
        solidTop=.92,surfaceUnitsPerLevel=96},
    source={client='0.16.9.5.40743',bundle='assets/content/commonprefabs/playersuperior.bundle',
        assemblySha256='43a539f5ad00fccd87ee54a084d8dbe1c5f63d12f8d855c8a392d68b3a1deaf9',
        bundleSha256='7748e0158c70922db1afd9948f57a132f70932da57d082cbac706818474bde42',
        proceduralPathID='534754836718881386',
        -- Serialized TurnAwayEffector, not merely its C# field defaults.
        depthMetres=.42,playerDepthMetres=.65,inRate=5,outRate=5,blendRate=8,
        cameraShiftMetres=-.03,
        -- Rows: native X,Y,Z; FP, TP, severe FP, severe TP amplitudes.
        rifle={pathID='-5535562151496450976',
            pos={{.05,.07,.14,.05},{.4,.27,.07,-.02},{.03,-.1,0,.03}},
            rot={{0,0,3.5,8.92},{-25,-43.22,0,-15},{-3,-3,-56.89,-56.2}}},
        handgun={pathID='8305763904142987496',
            pos={{.05,.05,.15,.05},{.4,.27,.07,0},{.03,.03,.03,.03}},
            rot={{0,0,16.94,8.92},{-25,-70,-15,-15},{-3,-3,-60,-72.6}}},
        prone={pathID='8777019209849567398',
            pos={{.05,0,.05,0},{.4,.03,.4,.03},{.03,0,.03,0}},
            rot={{-9,-8.73,-9,-8.73},{-25,-25,-25,-25},{-3,0,-3,-43.95}}},
        elbows={left={{-.3,0,.16,-.4},{0,-.2,0,-.4},{0,.15,0,0}},
            right={{-.3,-.1,.16,0},{0,-.25,0,-1},{0,-.2,0,-1}}}},
    -- Own PZ proposals: pull back first, then turn out of the way; these are
    -- consumed by the future GripIK layer, never applied by WeaponObstacle.
    classes={
        rifle={pose='highReady',pullUntil=.35,maxBackMetres=.14,maxUpMetres=.08,
            maxPitchDegrees=62,elbowOutMetres=.07,minChestClearanceMetres=.06},
        handgun={pose='chestDown',pullUntil=.4,maxBackMetres=.10,maxUpMetres=0,
            maxPitchDegrees=-65,elbowOutMetres=.05,minChestClearanceMetres=.06}}
}
return D
