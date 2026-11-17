package pz.escapefromzomboidfirearms.zb;

/** Generated explicit PZ collider choices; tools/penetration_world.py. */
public final class ShotWorldMaterials {
    private ShotWorldMaterials() {}
    public record Rule(String category,String property,String value,String preset,String reason) {}
    public static final java.util.List<Rule> RULES=java.util.List.of(
        new Rule("window","state","intact-pane","Glass","Closed intact unbarricaded IsoWindow pane, aperture face."),
        new Rule("barricade","state","wood-plank","WoodThin","Wooden plank IsoBarricade nailed across window or door."),
        new Rule("barricade","state","metal-sheet","MetalThin","Sheet-metal IsoBarricade welded across window or door."),
        new Rule("barricade","state","metal-bars","Grate","Metal-bar IsoBarricade welded across window or door."),
        new Rule("vehicle","part","window","Glass","Installed intact closed VehicleWindow; explicit proxy area, not GlassVisor or shattered pane."),
        new Rule("vehicle","part","body","MetalThin","Installed native VehicleDoor including hood/trunk; project sheet-metal adaptation, not measured thickness."),
        new Rule("vehicle","part","engine","MetalThick","Native VehicleParts.getEngine component; generic thick metal, no penetration variant or invented thickness."),
        new Rule("vehicle","part","tyre","Rubber","Installed Tire matching the native wheel ID; offset/radius/width proxy, not brake/suspension or rim material."),
        new Rule("curtain","CurtainSound","Shade","Plastic","Closed roller or venetian window shade."),
        new Rule("curtain","CurtainSound","Short","Fabric","Closed short fabric window curtain."),
        new Rule("curtain","CurtainSound","Long","Fabric","Closed long fabric window curtain."),
        new Rule("curtain","CurtainSound","Sheet","Fabric","Closed cloth sheet hung over aperture."),
        new Rule("curtain","state","closed-sheet","Fabric","Closed IsoCurtain without explicit MaterialType or CurtainSound."),
        new Rule("door","DoorSound","PrisonMetalDoor","Grate","Barred prison cell door allows rounds through/between bars."),
        new Rule("door","DoorSound","SlidingGlassDoor","Glass","Closed sliding glass patio/storefront door."),
        new Rule("door","MaterialType","Glass","Glass","Glazed door or full-window garage panel."),
        new Rule("door","MaterialType","Glass_Light","Glass","Light glazed door panel."),
        new Rule("door","MaterialType","Glass_Solid","Glass","Solid glazed door panel."),
        new Rule("door","Material","MetalWire","Chainfence","Chainlink wire gate."),
        new Rule("door","Material2","MetalWire","Chainfence","Chainlink wire gate with pipe frame."),
        new Rule("door","Material","MetalBars","Grate","Wrought-iron or metal-bar gate."),
        new Rule("door","Material2","MetalBars","Grate","Tubular or metal-bar pole/farm gate."),
        new Rule("door","DoorSound","MetalPoleGateDouble","Grate","Double metal pole gate."),
        new Rule("door","DoorSound","MetalPoleGate","Grate","Single metal pole gate."),
        new Rule("door","DoorSound","MetalPoleGateSmall","Grate","Small metal pole gate."),
        new Rule("door","DoorSound","FarmGate","Grate","Open tubular farm gate."),
        new Rule("door","DoorSound","MetalGate","Grate","Open metal bar/wire gate."),
        new Rule("door","Material","Log","WoodThick","Heavy log fort gate."),
        new Rule("door","DoorSound","WoodLogGate","WoodThick","Heavy log fort gate by explicit DoorSound."),
        new Rule("door","DoorSound","GarageDoor","MetalThin","Roll-up sheet-metal garage door."),
        new Rule("door","Material","MetalPlates","MetalThin","Commercial sheet-metal door leaf (overrides mis-tagged Plastic MaterialType on fixtures_doors_02)."),
        new Rule("door","MaterialType","Metal_Light","MetalThin","Light sheet-metal door."),
        new Rule("door","MaterialType","Metal","MetalThin","Standard hollow-metal commercial/security door leaf."),
        new Rule("door","Material2","PlasticHard","Plastic","Molded PVC/portable-toilet door leaf."),
        new Rule("door","DoorSound","MetalDoor","MetalThin","Commercial metal door identified by DoorSound."),
        new Rule("door","MaterialType","Wood","WoodThin","Standard wooden door leaf."),
        new Rule("door","MaterialType","Wood_Solid","WoodThin","Vanilla interior/exterior wooden door leaf or plank gate (thin panel, not log wall)."),
        new Rule("door","DoorSound","WoodDoor","WoodThin","Standard wooden door identified by DoorSound."),
        new Rule("door","DoorSound","WoodGate","WoodThin","Wooden plank fence gate."),
        new Rule("door","DoorSound","WoodGateSmall","WoodThin","Low wooden picket/plank gate."),
        new Rule("door","DoorSound","WoodShackDoor","WoodThin","Rough wooden shack door."),
        new Rule("door","Material","Wood","WoodThin","Untyped wooden door leaf with Wood resource."),
        new Rule("door","Material2","Wood","WoodThin","Untyped wooden door/gate leaf with Wood secondary resource."),
        new Rule("door","Material","Door","WoodThin","Untyped door leaf with generic Door resource."),
        new Rule("fence","Material","MetalPlates","MetalThin","Corrugated sheet-metal plate fence (overrides stale Concrete/Metal_Large MaterialType on fencing_01_40)."),
        new Rule("fence","MaterialType","Concrete","Concrete","Concrete perimeter barrier."),
        new Rule("fence","MaterialType","Cinderblock","Concrete","Cinderblock perimeter barrier."),
        new Rule("fence","MaterialType","Brick","Stone","Brick low wall or pillar fence."),
        new Rule("fence","MaterialType","Stone","Stone","Stone low wall."),
        new Rule("fence","Material","Stone","Stone","Untyped low stone wall."),
        new Rule("fence","Material","Brick","Stone","Untyped low brick wall."),
        new Rule("fence","FenceTypeLow","RoadBlock","Concrete","Raised masonry flowerbed border or concrete parking curb."),
        new Rule("fence","FenceTypeLow","Sandbag","Fabric_HiPen","Sandbag defensive barricade."),
        new Rule("fence","Material","Sandbag","Fabric_HiPen","Player/world sandbag wall."),
        new Rule("fence","FenceTypeLow","Barbwire","Chainfence","Barbed-wire fence lets rounds pass cleanly."),
        new Rule("fence","Material","MetalWire","Chainfence","Colliding native fence with explicit wire material; generic metal fences are not wire."),
        new Rule("fence","Material2","MetalWire","Chainfence","Fence with secondary wire mesh material."),
        new Rule("fence","MaterialType","Glass","Glass","Glass balcony balustrade."),
        new Rule("fence","MaterialType","Glass_Light","Glass","Light glass balustrade."),
        new Rule("fence","MaterialType","Glass_Solid","Glass","Solid glass balustrade."),
        new Rule("fence","Material","MetalBars","Grate","Colliding native fence with explicit metal bars."),
        new Rule("fence","Material2","MetalBars","Grate","Colliding native fence with secondary metal bars."),
        new Rule("fence","FenceTypeLow","MetalGate","Grate","Low wrought-iron/metal-bar railing."),
        new Rule("fence","FenceTypeHigh","MetalGate","Grate","Tall wrought-iron/metal-bar fence."),
        new Rule("fence","Material","Log","WoodThick","Log palisade fence."),
        new Rule("fence","MaterialType","Metal_Large","MetalThick","Thick large-plate metal barrier."),
        new Rule("fence","MaterialType","Metal_Light","MetalThin","Corrugated or light sheet-metal fence."),
        new Rule("fence","MaterialType","Metal","MetalThin","Sheet-metal or light tubular fence."),
        new Rule("fence","Material","SmallMetalPlates","MetalThin","Small sheet-metal fence."),
        new Rule("fence","Material","MetalPipe","MetalThin","Tubular metal pipe rail."),
        new Rule("fence","FenceTypeLow","Metal","MetalThin","Low metal rail or barrier."),
        new Rule("fence","FenceTypeHigh","Metal","MetalThin","Tall sheet-metal fence."),
        new Rule("fence","MaterialType","Wood","WoodThin","Wooden picket/plank fence."),
        new Rule("fence","MaterialType","Wood_Solid","WoodThin","Wooden board privacy/ranch fence (plank thickness, distinct from Log palisade)."),
        new Rule("fence","Material","Wood","WoodThin","Untyped wooden fence with Wood resource."),
        new Rule("fence","FenceTypeLow","Wood","WoodThin","Low wooden picket/rail fence."),
        new Rule("fence","FenceTypeHigh","Wood","WoodThin","Tall wooden board fence."),
        new Rule("fence","FenceTypeLow","Other","Grate","Open porch/deck balustrade railing."),
        new Rule("fence","state","open-railing","Grate","Open hoppable railing/banister without opaque wall surface."),
        new Rule("thumpable","Material","Sandbag","Fabric_HiPen","Constructed sandbag wall."),
        new Rule("thumpable","MaterialType","Wood","WoodThin","Native planar player construction with explicit Wood; no HP or thickness inference."),
        new Rule("thumpable","MaterialType","Wood_Solid","WoodThick","Native planar construction explicitly identifies solid wood."),
        new Rule("thumpable","MaterialType","Metal_Light","MetalThin","Native planar construction explicitly identifies light metal."),
        new Rule("thumpable","MaterialType","Metal","MetalThick","Native planar construction uses the generic Metal preset, not a penetration variant."),
        new Rule("thumpable","Material","Wood","WoodThin","Untyped vanilla wooden door/window frames identify Wood directly; explicit MaterialType takes precedence."),
        new Rule("thumpable","Material","Log","WoodThick","Untyped planar log construction identifies the Log resource; no guessed thickness."),
        new Rule("thumpable","Material","MetalWire","Chainfence","Untyped native planar wire construction explicitly identifies MetalWire."),
        new Rule("thumpable","Material","MetalBars","Grate","Untyped native planar bar construction explicitly identifies MetalBars."),
        new Rule("tree","Material","Log","WoodThick","Native tree flag and explicit Log resource identify a wooden trunk."),
        new Rule("tree","state","trunk","WoodThick","Centered IsoTree/tree trunk cylinder box when sprite omits Material=Log."),
        new Rule("window-frame","Material","Wood","WoodThin","Untyped wooden window frame."),
        new Rule("window-frame","Material","Log","WoodThick","Log window frame."),
        new Rule("window-frame","Material","Brick","Stone","Brick window frame."),
        new Rule("window-frame","Material","Stone","Stone","Stone window frame."),
        new Rule("window-frame","Material","PlasticHard","Plastic","Molded plastic window frame."),
        new Rule("window-frame","IsPaintable","","WoodThick_MedPen","Paintable interior plaster/wood window frame without explicit MaterialType."),
        new Rule("door-frame","Material","Wood","WoodThin","Untyped wooden doorway lintel/frame."),
        new Rule("door-frame","Material","Log","WoodThick","Log doorway lintel/frame."),
        new Rule("door-frame","Material","Brick","Stone","Brick doorway lintel/frame."),
        new Rule("door-frame","Material","Stone","Stone","Stone doorway lintel/frame."),
        new Rule("door-frame","Material","PlasticHard","Plastic","Molded plastic bathroom stall doorway partition."),
        new Rule("door-frame","IsPaintable","","WoodThick_MedPen","Paintable interior plaster/wood doorway lintel without explicit MaterialType."),
        new Rule("wall","container","fireplace","Stone","Masonry fireplace hearth wall."),
        new Rule("wall","Material","Stone","Stone","Untyped stone wall."),
        new Rule("wall","Material","Brick","Stone","Untyped brick wall."),
        new Rule("wall","Material","Wood","WoodThin","Untyped wooden wall."),
        new Rule("wall","Material","Log","WoodThick","Untyped log wall."),
        new Rule("furniture","container","tent","Fabric","Canvas camping tent."),
        new Rule("furniture","container","stonefurnace","Stone","Crafted stone furnace."),
        new Rule("furniture","MaterialType","Concrete","Concrete","Concrete monument, barrier or planter."),
        new Rule("furniture","MaterialType","Cinderblock","Concrete","Cinderblock planter or stand."),
        new Rule("furniture","MaterialType","Brick","Stone","Brick planter or hearth."),
        new Rule("furniture","MaterialType","Stone","Stone","Cut stone bird bath, monument or block."),
        new Rule("furniture","MaterialType","Wood_Solid","WoodThin","Wooden furniture/counters/crates use board panels rather than solid log thickness."),
        new Rule("furniture","MaterialType","Metal_Large","MetalThin","Sheet-metal appliances, lockers and dumpsters (distinct from armor-plate walls)."),
        new Rule("furniture","MaterialType","Metal","MetalThin","Sheet-metal filing cabinets, shelves and appliances."),
        new Rule("furniture","Material","Wood","WoodThin","Untyped wooden furniture/crate/workbench."),
        new Rule("furniture","Material2","Wood","WoodThin","Untyped furniture with secondary Wood resource."),
        new Rule("furniture","Material","Log","WoodThick","Heavy log bench/table."),
        new Rule("furniture","Material","Fabric","Fabric","Untyped upholstered/fabric object."),
        new Rule("furniture","Material","Paper","Cardboard","Cardboard boxes/paper stacks."),
        new Rule("furniture","Material","Fridge","MetalThin","Sheet-metal refrigeration unit."),
        new Rule("furniture","Material","MetalPlates","MetalThin","Untyped sheet-metal locker/container."),
        new Rule("furniture","Material","SmallMetalPlates","MetalThin","Untyped light sheet-metal container."),
        new Rule("furniture","Material","MetalScrap","MetalThin","Scrap-metal object."),
        new Rule("furniture","Material","MetalPipe","MetalThin","Tubular metal stand/rack."),
        new Rule("furniture","Material","Pipes","MetalThin","Plumbing pipe fixture."),
        new Rule("furniture","Material","Plumbing","Tile","Ceramic bathroom/sink plumbing fixture."),
        new Rule("furniture","Material","Electric","MetalThin","Sheet-metal electrical appliance/cabinet."),
        new Rule("furniture","Material","Mechanical","MetalThick","Heavy cast/mechanical industrial machinery."),
        new Rule("furniture","Material","MetalBars","Grate","Open metal-bar rack or cage."),
        new Rule("furniture","Material","MetalWire","Chainfence","Wire cage or rack."),
        new Rule("furniture","Material","PlasticHard","Plastic","Molded plastic bin/fixture."),
        new Rule("furniture","Material","Glass","Glass","Glass display case or fixture."),
        new Rule("furniture","Material","Stone","Stone","Cut stone monument, boulder or block."),
        new Rule("furniture","Material","Brick","Stone","Brick planter or hearth."),
        new Rule("*","MaterialType","Wood","WoodThin","Vanilla Wood surface; use the generic thin wood preset, no thickness multiplier."),
        new Rule("*","MaterialType","Wood_Solid","WoodThick","Vanilla explicitly distinguishes solid wood walls from Wood."),
        new Rule("*","MaterialType","Plaster","WoodThick_MedPen","Interior drywall/plasterboard stud partition wall; rifle-penetrable medium barrier."),
        new Rule("*","MaterialType","Metal_Light","MetalThin","Vanilla explicitly identifies light metal."),
        new Rule("*","MaterialType","Metal","MetalThick","Generic metal wall uses the generic thick preset; no Hi/Med/Low variant inferred."),
        new Rule("*","MaterialType","Metal_Large","MetalThick","Large metal wall surface uses generic thick metal; size is not converted to thickness."),
        new Rule("*","MaterialType","Metal_Solid","MetalThick","Vanilla explicitly identifies solid metal."),
        new Rule("*","MaterialType","Concrete","Concrete","Exact vanilla concrete material."),
        new Rule("*","MaterialType","Cinderblock","Concrete","Explicit concrete masonry block; Tarkov has no separate brick/block collider preset."),
        new Rule("*","MaterialType","Brick","Stone","Explicit masonry; Stone is the project adaptation because no Brick preset exists."),
        new Rule("*","MaterialType","Stone","Stone","Exact vanilla stone material."),
        new Rule("*","MaterialType","Ceramic","Tile","Explicit ceramic surface maps to the Tarkov ceramic tile preset."),
        new Rule("*","MaterialType","Fabric","Fabric","Exact vanilla fabric surface, separate from anatomical tissue."),
        new Rule("*","MaterialType","Carpet","Fabric","Exact vanilla carpet/rug textile surface."),
        new Rule("*","MaterialType","Plastic","Plastic","Exact vanilla plastic material."),
        new Rule("*","MaterialType","Rubber","Rubber","Exact vanilla rubber material; does not infer a vehicle tyre."),
        new Rule("*","MaterialType","Sand","Pebbles_MedPen","Loose sand surface when not a Sandbag barricade."),
        new Rule("*","MaterialType","Grass","GrassLow","Grass/thatch surface lets projectiles pass."),
        new Rule("*","MaterialType","Glass","Glass","Exact vanilla glass surface."),
        new Rule("*","MaterialType","Glass_Light","Glass","Vanilla light glass surface; no invented thickness correction."),
        new Rule("*","MaterialType","Glass_Solid","Glass","Vanilla solid glass surface; no invented thickness correction."),
        new Rule("*","container","crate","WoodThin","Untyped wooden storage crate when MaterialType/Material are omitted."),
        new Rule("*","CustomName","Bed","WoodThin","Untyped bed/gurney frame when MaterialType/Material are omitted."),
        new Rule("*","CustomName","Chair","WoodThin","Untyped chair frame when MaterialType/Material are omitted."),
        new Rule("*","CustomName","Table","WoodThin","Untyped table when MaterialType/Material are omitted."),
        new Rule("*","CustomName","Bench","WoodThin","Untyped bench when MaterialType/Material are omitted."),
        new Rule("*","CustomName","Counter","WoodThin","Untyped counter cabinet when MaterialType/Material are omitted."),
        new Rule("*","CustomName","Couch","Fabric","Untyped upholstered couch when MaterialType/Material are omitted."),
        new Rule("*","state","pole","MetalThin","Untyped streetlight/signpost pole when MaterialType/Material are omitted.")
    );
    public static String lookup(String category,String materialType,String material) {
        return lookup(category,key->"MaterialType".equals(key)?materialType:"Material".equals(key)?material:null);
    }
    public static String lookup(String category,java.util.function.UnaryOperator<String> properties) {
        if (category.equals("vehicle") || category.equals("unknown") || properties==null) return null;
        String materialType=properties.apply("MaterialType");
        // A typed construction cannot fall back to its resource when the surface is unknown.
        if (category.equals("thumpable") && materialType!=null && !materialType.isBlank()) {
            for (Rule r:RULES) if (r.property.equals("MaterialType")
                    && (r.category.equals(category) || r.category.equals("*"))
                    && r.value.equals(materialType)) return r.preset;
            return null;
        }
        // Specific rules precede generic MaterialType rows (wire vs metal fence, door vs wall).
        for (Rule r:RULES) if (r.category.equals(category) && matches(r,properties)) return r.preset;
        for (Rule r:RULES) if (r.category.equals("*") && matches(r,properties)) return r.preset;
        return null;
    }
    public static String vehicle(String component) {
        for (Rule r:RULES) if (r.category.equals("vehicle") && r.property.equals("part") && r.value.equals(component)) return r.preset;
        return null;
    }
    private static boolean matches(Rule r,java.util.function.UnaryOperator<String> properties) {
        String actual=properties.apply(r.property);
        return actual!=null && r.value.equals(actual);
    }
    public static String preset(String token) {
        int index=token.indexOf("|b5=");
        if (index<0) return token.equals("glass")?"Glass":null;
        String name=token.substring(index+4);
        return RULES.stream().anyMatch(r->r.preset.equals(name))?name:null;
    }
    public static String nativeMaterial(String token) {
        int index=token.indexOf("|b5=");return index<0?token:token.substring(0,index);
    }
}
