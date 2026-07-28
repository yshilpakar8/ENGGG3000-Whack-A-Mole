import math
import random
import pygame

pygame.init()

# --------------------------------------------------
# GAME SETTINGS
# --------------------------------------------------

SCREEN_WIDTH = 1200
SCREEN_HEIGHT = 700
FPS = 60

ROUND_TIME = 60
HITS_PER_LEVEL = 5
MAX_LEVEL = 10

# --------------------------------------------------
# COLOURS
# --------------------------------------------------

WHITE = (255, 255, 255)
BLACK = (25, 25, 25)
BLUE = (45, 110, 230)
LIGHT_BLUE = (220, 240, 255)

GREEN = (115, 195, 85)
DARK_GREEN = (90, 165, 65)

BROWN = (145, 90, 50)
LIGHT_BROWN = (210, 155, 95)
PINK = (230, 110, 135)

RED = (225, 65, 65)
GOLD = (255, 190, 60)
GREY = (90, 100, 115)


# --------------------------------------------------
# PLAYER INPUT
# --------------------------------------------------

def get_player_position():
    """
    The mouse represents the player's body position for now.

    Later, replace this with calibrated sensor coordinates:

    sensor_x, sensor_y = receive_sensor_data()
    return screen_x, screen_y
    """

    return pygame.mouse.get_pos()


# --------------------------------------------------
# MOLE
# --------------------------------------------------

class Mole:
    def __init__(self, level):
        # Mole becomes smaller at higher levels.
        self.radius = max(38, 72 - ((level - 1) * 3))

        # Mole disappears faster at higher levels.
        self.lifetime = max(
            450,
            1500 - ((level - 1) * 110)
        )

        self.x = random.randint(
            self.radius + 30,
            SCREEN_WIDTH - self.radius - 30
        )

        self.y = random.randint(
            180,
            SCREEN_HEIGHT - self.radius - 40
        )

        self.spawn_time = pygame.time.get_ticks()

    def has_expired(self):
        current_time = pygame.time.get_ticks()
        elapsed_time = current_time - self.spawn_time

        return elapsed_time >= self.lifetime

    def remaining_fraction(self):
        current_time = pygame.time.get_ticks()
        elapsed_time = current_time - self.spawn_time

        fraction = 1 - (elapsed_time / self.lifetime)

        return max(0, min(1, fraction))


# --------------------------------------------------
# MAIN GAME
# --------------------------------------------------

class WhackAMoleGame:
    def __init__(self):
        self.screen = pygame.display.set_mode(
            (SCREEN_WIDTH, SCREEN_HEIGHT)
        )

        pygame.display.set_caption(
            "Full-Body Whack-a-Mole"
        )

        self.clock = pygame.time.Clock()

        self.small_font = pygame.font.SysFont(
            "arial",
            22
        )

        self.medium_font = pygame.font.SysFont(
            "arial",
            32,
            bold=True
        )

        self.large_font = pygame.font.SysFont(
            "arial",
            58,
            bold=True
        )

        self.title_font = pygame.font.SysFont(
            "arial",
            72,
            bold=True
        )

        self.game_state = "START"

        self.score = 0
        self.hits = 0
        self.misses = 0
        self.level = 1

        self.round_start_time = 0

        self.mole = None

        self.cursor_radius = 30

        self.player_x = SCREEN_WIDTH // 2
        self.player_y = SCREEN_HEIGHT // 2

    # --------------------------------------------------
    # GAME CONTROL
    # --------------------------------------------------

    def start_game(self):
        self.score = 0
        self.hits = 0
        self.misses = 0
        self.level = 1

        self.round_start_time = pygame.time.get_ticks()

        self.game_state = "PLAYING"

        self.create_new_mole()

    def create_new_mole(self):
        self.mole = Mole(self.level)

    def get_remaining_time(self):
        current_time = pygame.time.get_ticks()

        elapsed_seconds = (
            current_time - self.round_start_time
        ) / 1000

        return max(
            0,
            ROUND_TIME - elapsed_seconds
        )

    def update_game(self):
        self.player_x, self.player_y = (
            get_player_position()
        )

        if self.game_state != "PLAYING":
            return

        if self.get_remaining_time() <= 0:
            self.game_state = "GAME_OVER"
            self.mole = None
            return

        if self.mole is None:
            self.create_new_mole()
            return

        distance_to_mole = math.dist(
            (self.player_x, self.player_y),
            (self.mole.x, self.mole.y)
        )

        collision_distance = (
            self.cursor_radius +
            (self.mole.radius * 0.72)
        )

        # Player moved onto the mole.
        if distance_to_mole <= collision_distance:
            self.hits += 1

            self.level = min(
                MAX_LEVEL,
                1 + (self.hits // HITS_PER_LEVEL)
            )

            self.score += 10 * self.level

            self.create_new_mole()

        # Player did not reach the mole in time.
        elif self.mole.has_expired():
            self.misses += 1

            self.create_new_mole()

    # --------------------------------------------------
    # DRAWING
    # --------------------------------------------------

    def draw_text(
        self,
        text,
        font,
        colour,
        centre_position
    ):
        text_surface = font.render(
            text,
            True,
            colour
        )

        text_rectangle = text_surface.get_rect(
            center=centre_position
        )

        self.screen.blit(
            text_surface,
            text_rectangle
        )

    def draw_background(self):
        self.screen.fill(LIGHT_BLUE)

        playing_field = pygame.Rect(
            0,
            135,
            SCREEN_WIDTH,
            SCREEN_HEIGHT - 135
        )

        pygame.draw.rect(
            self.screen,
            GREEN,
            playing_field
        )

        stripe_height = 55

        for index, y_position in enumerate(
            range(
                135,
                SCREEN_HEIGHT,
                stripe_height
            )
        ):
            if index % 2 == 0:
                pygame.draw.rect(
                    self.screen,
                    DARK_GREEN,
                    pygame.Rect(
                        0,
                        y_position,
                        SCREEN_WIDTH,
                        stripe_height
                    )
                )

    def draw_information_panel(self):
        pygame.draw.rect(
            self.screen,
            WHITE,
            pygame.Rect(
                0,
                0,
                SCREEN_WIDTH,
                135
            )
        )

        self.draw_text(
            f"SCORE: {self.score}",
            self.medium_font,
            BLACK,
            (150, 45)
        )

        self.draw_text(
            f"LEVEL: {self.level}",
            self.medium_font,
            BLACK,
            (SCREEN_WIDTH // 2, 45)
        )

        remaining_time = math.ceil(
            self.get_remaining_time()
        )

        self.draw_text(
            f"TIME: {remaining_time}",
            self.medium_font,
            BLACK,
            (SCREEN_WIDTH - 150, 45)
        )

        progress_bar_width = 500
        progress_bar_x = (
            SCREEN_WIDTH - progress_bar_width
        ) // 2

        pygame.draw.rect(
            self.screen,
            (215, 225, 235),
            pygame.Rect(
                progress_bar_x,
                88,
                progress_bar_width,
                18
            ),
            border_radius=9
        )

        hits_in_current_level = (
            self.hits % HITS_PER_LEVEL
        )

        progress = (
            hits_in_current_level /
            HITS_PER_LEVEL
        )

        pygame.draw.rect(
            self.screen,
            BLUE,
            pygame.Rect(
                progress_bar_x,
                88,
                int(progress_bar_width * progress),
                18
            ),
            border_radius=9
        )

        if self.level < MAX_LEVEL:
            hits_needed = (
                HITS_PER_LEVEL -
                hits_in_current_level
            )

            message = (
                f"{hits_needed} hit(s) "
                "until the next level"
            )
        else:
            message = "Maximum level reached"

        self.draw_text(
            message,
            self.small_font,
            GREY,
            (SCREEN_WIDTH // 2, 119)
        )

    def draw_mole(self):
        if self.mole is None:
            return

        x = self.mole.x
        y = self.mole.y
        radius = self.mole.radius

        # Hole
        pygame.draw.ellipse(
            self.screen,
            BLACK,
            pygame.Rect(
                x - radius,
                y + int(radius * 0.35),
                radius * 2,
                int(radius * 0.72)
            )
        )

        # Mole body
        pygame.draw.ellipse(
            self.screen,
            BROWN,
            pygame.Rect(
                x - int(radius * 0.72),
                y - int(radius * 0.78),
                int(radius * 1.44),
                int(radius * 1.62)
            )
        )

        # Ears
        ear_radius = max(
            6,
            int(radius * 0.17)
        )

        pygame.draw.circle(
            self.screen,
            LIGHT_BROWN,
            (
                x - int(radius * 0.56),
                y - int(radius * 0.38)
            ),
            ear_radius
        )

        pygame.draw.circle(
            self.screen,
            LIGHT_BROWN,
            (
                x + int(radius * 0.56),
                y - int(radius * 0.38)
            ),
            ear_radius
        )

        # Eyes
        eye_y = y - int(radius * 0.28)
        eye_distance = int(radius * 0.24)
        eye_radius = max(
            3,
            int(radius * 0.07)
        )

        pygame.draw.circle(
            self.screen,
            BLACK,
            (
                x - eye_distance,
                eye_y
            ),
            eye_radius
        )

        pygame.draw.circle(
            self.screen,
            BLACK,
            (
                x + eye_distance,
                eye_y
            ),
            eye_radius
        )

        # Nose
        nose_y = y + int(radius * 0.02)

        pygame.draw.circle(
            self.screen,
            PINK,
            (x, nose_y),
            max(
                5,
                int(radius * 0.10)
            )
        )

        # Mouth
        pygame.draw.line(
            self.screen,
            BLACK,
            (
                x,
                nose_y + 6
            ),
            (
                x,
                nose_y + 17
            ),
            2
        )

        # Whiskers
        for direction in (-1, 1):
            for offset in (-8, 0, 8):
                pygame.draw.line(
                    self.screen,
                    BLACK,
                    (
                        x + direction *
                        int(radius * 0.12),
                        nose_y + offset // 2
                    ),
                    (
                        x + direction *
                        int(radius * 0.58),
                        nose_y + offset
                    ),
                    2
                )

        # Countdown ring
        fraction = (
            self.mole.remaining_fraction()
        )

        if fraction > 0.35:
            ring_colour = GOLD
        else:
            ring_colour = RED

        ring_rectangle = pygame.Rect(
            x - radius - 9,
            y - radius - 9,
            (radius + 9) * 2,
            (radius + 9) * 2
        )

        pygame.draw.arc(
            self.screen,
            ring_colour,
            ring_rectangle,
            -math.pi / 2,
            -math.pi / 2 +
            (math.tau * fraction),
            7
        )

    def draw_player_cursor(self):
        cursor_size = (
            self.cursor_radius * 2 + 12
        )

        cursor_surface = pygame.Surface(
            (cursor_size, cursor_size),
            pygame.SRCALPHA
        )

        centre = self.cursor_radius + 6

        pygame.draw.circle(
            cursor_surface,
            (*BLUE, 60),
            (centre, centre),
            self.cursor_radius
        )

        pygame.draw.circle(
            cursor_surface,
            (*BLUE, 240),
            (centre, centre),
            self.cursor_radius,
            5
        )

        pygame.draw.line(
            cursor_surface,
            BLUE,
            (
                centre - 11,
                centre
            ),
            (
                centre + 11,
                centre
            ),
            3
        )

        pygame.draw.line(
            cursor_surface,
            BLUE,
            (
                centre,
                centre - 11
            ),
            (
                centre,
                centre + 11
            ),
            3
        )

        self.screen.blit(
            cursor_surface,
            (
                self.player_x - centre,
                self.player_y - centre
            )
        )

    def draw_start_screen(self):
        overlay = pygame.Surface(
            (SCREEN_WIDTH, SCREEN_HEIGHT),
            pygame.SRCALPHA
        )

        overlay.fill(
            (15, 28, 48, 195)
        )

        self.screen.blit(
            overlay,
            (0, 0)
        )

        self.draw_text(
            "FULL-BODY",
            self.large_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                210
            )
        )

        self.draw_text(
            "WHACK-A-MOLE",
            self.title_font,
            GOLD,
            (
                SCREEN_WIDTH // 2,
                295
            )
        )

        self.draw_text(
            "Move the blue cursor onto the mole",
            self.medium_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                390
            )
        )

        self.draw_text(
            "before it disappears.",
            self.medium_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                432
            )
        )

        self.draw_text(
            "Press SPACE to start",
            self.medium_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                515
            )
        )

        self.draw_text(
            "Press ESC to exit",
            self.small_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                650
            )
        )

    def draw_game_over_screen(self):
        overlay = pygame.Surface(
            (SCREEN_WIDTH, SCREEN_HEIGHT),
            pygame.SRCALPHA
        )

        overlay.fill(
            (15, 28, 48, 205)
        )

        self.screen.blit(
            overlay,
            (0, 0)
        )

        self.draw_text(
            "ROUND COMPLETE",
            self.large_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                205
            )
        )

        self.draw_text(
            f"SCORE: {self.score}",
            self.title_font,
            GOLD,
            (
                SCREEN_WIDTH // 2,
                295
            )
        )

        self.draw_text(
            f"Hits: {self.hits}",
            self.medium_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                390
            )
        )

        self.draw_text(
            f"Misses: {self.misses}",
            self.medium_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                435
            )
        )

        self.draw_text(
            f"Level reached: {self.level}",
            self.medium_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                480
            )
        )

        self.draw_text(
            "Press SPACE to play again",
            self.medium_font,
            WHITE,
            (
                SCREEN_WIDTH // 2,
                555
            )
        )

    def draw_game(self):
        self.draw_background()

        if self.game_state == "PLAYING":
            self.draw_information_panel()
            self.draw_mole()

        elif self.game_state == "START":
            self.draw_start_screen()

        elif self.game_state == "GAME_OVER":
            self.draw_game_over_screen()

        self.draw_player_cursor()

        pygame.display.flip()

    # --------------------------------------------------
    # MAIN LOOP
    # --------------------------------------------------

    def run(self):
        running = True

        while running:
            self.clock.tick(FPS)

            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    running = False

                elif event.type == pygame.KEYDOWN:
                    if event.key == pygame.K_ESCAPE:
                        running = False

                    elif event.key == pygame.K_SPACE:
                        if self.game_state in (
                            "START",
                            "GAME_OVER"
                        ):
                            self.start_game()

            self.update_game()
            self.draw_game()

        pygame.quit()


game = WhackAMoleGame()
game.run()