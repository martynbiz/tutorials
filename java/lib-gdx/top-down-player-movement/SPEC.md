# Top-Down Player Movement (libGDX tutorial series)

A five-guide libGDX tutorial series that builds the basis of a top-down fantasy game: a player character that walks in 8 directions on the arrow keys, animated from the reader's own sprite sheet, with an attack animation.

## Context

The tutorials repo has Java series on Spring Boot but nothing on games. Readers who want to make a top-down game usually get stuck on the same first problems:

- moving a character in 8 directions without diagonals being faster
- cutting frames out of a sprite sheet and turning them into animations
- switching between idle, walk and attack animations without the sprite jumping about

This series covers exactly those problems and leaves a structure that can grow into a game.

The series lives in `java/lib-gdx/top-down-player-movement/`. It's written for **any** top-down 8-direction sprite sheet the reader chooses, so it doesn't ship, name or link a particular sheet.

## Goals

- A reader who knows Java but has never used libGDX or Ashley ends up with a desktop game in which:
  - the arrow keys move the player in 8 directions at the same speed on the diagonals
  - the player character shows idle, walk and attack animations from the reader's own sprite sheet, facing the right way
  - Space plays an attack in the direction the player faces, then returns to idle or walk
- Readers can map **any** sprite sheet that meets the stated requirements, by editing one mapping block.
- The code is organised as an Ashley entity component system, so a later enemy reuses the same systems.
- Each guide ends with a game that runs and visibly does something new.

## Non-goals

These are deferred to "Where next" or to exercises:

- enemies, collisions and tile maps
- hit boxes and damage during attack frames
- the camera following the player, and keeping the player on screen
- acceleration and friction
- queued attacks and combos
- sheets that draw fewer than 8 directions and flip the rest
- `AssetManager`
- integer-only (pixel-perfect) scaling
- unit tests
- Android, iOS and web

The sprite sheet itself is also out of scope: no sheet is shipped, named or linked.

## Decisions

### Series format

- **A README plus five guides**, in the same format as the repo's other series (`01-….md` and so on), each with "Your turn" exercises (with solutions) and a checkpoint. Only the guides are committed, not the code.
- **The guides, in order:**

  | Guide | Title | Ends with |
  |---|---|---|
  | 01 | Drawing the player | A gdx-liftoff project and the game loop. One frame from the reader's sheet is drawn with `SpriteBatch`, through a camera and viewport, crisp and scaled up |
  | 02 | Reading a sprite sheet | The `SpriteSheet` class, a "Reading your sheet" checklist and table, and `Animation` playing one walk cycle on the spot |
  | 03 | Entities and systems | The same player rebuilt as an Ashley entity, with a `RenderSystem` and an `AnimationSystem`. Nothing changes on screen |
  | 04 | Eight-direction movement | `PlayerInputSystem` and `MovementSystem`, the `Direction` enum, normalised diagonals, and idle versus walk |
  | 05 | Attacking | The `ATTACK` state, playing once, frames centred on the feet, and a state diagram |

  *Rationale:* guide 03 changes nothing on screen, so readers learn Ashley on code they already understand. Movement comes before attacking because the attack needs the facing direction.

### Audience

- **Readers are comfortable with Java** (classes, interfaces, enums, generics, collections, lambdas, running a Gradle or Maven build) **but new to libGDX and Ashley.** Every libGDX and Ashley class is explained the first time it appears. Basic Java isn't taught.

  *Rationale:* this matches the repo's other series, and it includes the readers most likely to search for "8-direction movement".

### Project setup

- **Generated with gdx-liftoff:** a Gradle project with its wrapper, desktop only (LWJGL3), with `core` and `lwjgl3` modules. Ashley is ticked in the generator's extensions, and one step shows the dependency line in `build.gradle`.
- **Versions** of libGDX, Ashley, gdx-liftoff, Gradle and Java are checked when building and pinned in a README versions table. The Java version is whatever actually builds with the Gradle version liftoff generates.

  *Rationale:* liftoff is the official generator. Desktop only avoids the Android SDK, and mobile has no arrow keys anyway.

### The reader's sprite sheet

- **Requirement:** a sheet, or several, with **8-direction idle, walk and attack** animations. Every frame in a file must be the same size. This is stated in the README, before guide 01.
- **`SpriteSheet` class, one per file:**
  - It takes the texture and the frame width and height, and works out its own column count (texture width ÷ frame width).
  - `frame(row, col)` and `framesSequence(row, col, count)` use flat numbering (row × columns + column). A sequence that runs past the end of a row deliberately carries on into the next row, and the guide says so.
  - Both return `TextureRegion`s.
  - A row or column that doesn't exist stops with a clear message, such as "row 9 doesn't exist: this sheet has 8 rows".

  *Rationale:* this is based on the author's existing `frame` and `framesSequence` helpers. A column count per sheet avoids the silent wrong frames you get from one `SHEET_COLS` constant when sheets differ in width.
- **One mapping block** is the only code a reader changes for their own sheet. For each animation and direction it gives the start row and column, the frame count and the frame duration. Each animation can also have an optional offset. The sheet paths are constants next to it.
- **Example numbers:** the guides show worked numbers as an illustration ("for example, a sheet with 24 px frames where walking starts at row 8, column 0…"). The numbers are realistic, but no sheet is named, and the text keeps reminding readers that their numbers will differ.
- **"Reading your sheet" checklist** in guide 02: open the sheet in any image viewer, find the frame size, count the columns, and note the start, frame count and direction for each animation. It ends with a table to fill in that matches the mapping block.

### Architecture (Ashley)

- **Systems are split by behaviour, not by character:**

  | System | Runs for entities with | Does |
  |---|---|---|
  | `PlayerInputSystem` | `PlayerControlled` | Turns the arrow keys and Space into a direction and an action, reading `Gdx.input` directly |
  | `MovementSystem` | `Velocity` | Moves the position on from the velocity |
  | `AnimationSystem` | `Animations`, `State` | Picks the animation for the current state and facing, and advances its timer |
  | `RenderSystem` | `Position`, `Sprite` | Draws the frame |

- **Components:** `Position`, `Velocity`, `PlayerControlled`, `Animations`, `State` and `Sprite`. The hero's `Animations` component is built from the mapping block.

  *Rationale:* one system per character (such as `HeroAnimationSystem`) means duplicating each behaviour for every new character. With systems split by behaviour, an enemy only needs an AI component in place of `PlayerControlled`.

### Movement

| Situation | Behaviour |
|---|---|
| Speed | A fixed speed in world units per second × delta time, about 60 px/s |
| Diagonals | The direction is normalised before speed is applied, taught in its own step with a worked example (√2 ≈ 1.41) |
| Opposite keys | They cancel. With ← + → only, the player stops and idles; ← + → + ↑ walks straight up |
| Releasing the keys | The player stops at once and idles, facing its last direction |
| Facing | Comes from the movement direction, mapped to one of 8 `Direction` enum values, and is stored separately from velocity |
| Edges | No limits. The player can walk off screen, and the camera stays put |

*Rationale:* this keeps the lesson on 8-direction movement rather than physics.

### Attack

| Situation | Behaviour |
|---|---|
| Key | Space, read with `Gdx.input.isKeyJustPressed`. It's set in one place so readers can change it |
| Direction | The direction faced when the attack starts, fixed for the whole attack |
| Movement | The player stands still while attacking. Arrow keys still count, and if any are held when the attack ends, the player walks straight on |
| Length | Plays once (`PlayMode.NORMAL`), then `isAnimationFinished` returns the state to idle or walk |
| Space during an attack | Ignored: no queuing and no combos |
| Different frame sizes | Every frame is drawn centred on the player's feet: the position is the bottom centre of the frame. A per-animation offset in the mapping block corrects sheets whose character isn't centred |
| Hits | None |

- **States:** `IDLE`, `WALK` and `ATTACK`, held in the `State` component, shown as a state diagram in guide 05.

  *Rationale:* attack frames are often larger than walk frames (32 px against 24 px, for example). Anchoring at the feet stops the player jumping when an attack starts.

### Rendering

| Topic | Choice |
|---|---|
| Camera | `OrthographicCamera` with a `FitViewport` of 320×180 world units, where 1 world unit is 1 sprite pixel |
| Window | Opens at 1280×720 (4×). A resized window gets black bars, not stretching |
| Sharpness | `TextureFilter.Nearest` on every sheet, with a short "why it's blurry" note |
| Background | A plain clear colour, with the player starting in the centre |
| Loading | `new Texture(...)` in `create()` and `dispose()` in `dispose()`, with an explanation of why textures have to be disposed |
| Sheet location | The project's `assets/` folder |

## Assumptions

- Readers' sheets are 8-direction, use one frame size per file, and have the character roughly centred in each frame. The offset covers sheets where it isn't.
- Current gdx-liftoff, libGDX and Ashley work together with a recent Java and Gradle on desktop.
- Readers are on desktop with a keyboard.
- A test sheet that meets the requirement is available for building and screenshots. It's kept outside the repo and never named in the guides.

## Open questions / Risks

- **Sheets vary:** some readers' sheets won't meet the requirement. The README must state it clearly before guide 01, so nobody finds out in guide 02.
- **No named sheet:** the example numbers and screenshots come from a pack readers can't get. The wording must keep saying "your numbers will differ", and checkpoints must be ones any sheet can pass.
- **Uneven scaling:** at window sizes that aren't a multiple of 320×180, `FitViewport` scales pixels unevenly. This is mentioned, not fixed.
- **Untested versions:** whether the chosen Java version works with liftoff's Gradle isn't checked yet. The README versions table depends on it.
- **The test sheet copy:** it's in the session scratchpad, which a reboot or cleanup can wipe. If it's lost, the author needs to supply a test sheet again.
- **The test sheet's layout:** it has no metadata, so which rows hold idle, walk and attack in each direction is worked out by eye while building. That's exactly what readers do with their own sheets, using guide 02's checklist.

## Verification

- **No automated tests**, by decision.
- **Building the series:** I build the reference project guide by guide against the test sheet, run it on desktop, and check each guide's checkpoint by hand:
  - 01: a frame drawn crisply in the centre
  - 02: a walk cycle playing on the spot
  - 03: the same on screen, now built from entities and systems
  - 04: all 8 directions, the same speed on diagonals, opposite keys cancelling, idling in the last direction
  - 05: the attack in all 8 directions, no jump between frame sizes, and a return to walking when keys are held
- **Exercises:** every "Your turn" exercise uses only material taught so far, and each solution is run before it's published.
- **Checkpoints:** each one is worded so a reader with any sheet that meets the requirement can check it.
