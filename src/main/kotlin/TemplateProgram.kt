import kotlinx.coroutines.*
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.noise.fbm
import org.openrndr.extra.noise.simplex
import org.openrndr.extra.olive.oliveProgram
import org.openrndr.extras.color.presets.DARK_GREEN
import org.openrndr.extras.color.presets.KHAKI
import org.openrndr.extras.color.presets.LIGHT_SKY_BLUE
import org.openrndr.extras.color.presets.LIGHT_YELLOW
import org.openrndr.launch
import org.openrndr.math.Vector3
import kotlin.math.*

@OptIn(DelicateCoroutinesApi::class)
fun main() = application {

    configure {
        width = 768
        height = 576
    }

    oliveProgram {
        val font = loadFont("data/fonts/default.otf", 14.0)

        val start = System.currentTimeMillis()

        val MAP_SIZE = 4096
        val N = MAP_SIZE * MAP_SIZE
        val RECT_SIZE = 8.0
        val MAP_WIDTH = MAP_SIZE * RECT_SIZE

        val widthHeightRatio = 10.0
        val sunAngle = Math.toRadians(25.0)
        val deltaH = RECT_SIZE * tan(sunAngle) / MAP_WIDTH * widthHeightRatio
        val sunDir = Vector3(-1.0, 0.0, 1.0).normalized

        val normalScale = 1 / MAP_WIDTH * 16.0

        println(deltaH)

        val colors = Array<ColorRGBa?>(N) { null }
        val map = DoubleArray(N)

        val lightRays = DoubleArray(N) { map[it].coerceAtLeast(0.0) }
        val shadows = DoubleArray(N)

        val d = MAP_SIZE / 8
        GlobalScope.launch {
            repeat(MAP_SIZE / d) { j -> GlobalScope.launch {
                val threadStart = System.currentTimeMillis()
                val y = j * d
                val indices = (y * MAP_SIZE until (y + d) * MAP_SIZE)

                var max = -100.0
                var min = 100.0
                indices.forEach { i ->
                    val y = i / MAP_SIZE * RECT_SIZE / MAP_SIZE / 16.0
                    val x = (i % MAP_SIZE) * RECT_SIZE / MAP_SIZE / 16.0
                    val octaves = 32
                    val lacunarity = 1.2
                    val gain = 0.85

                    map[i] = fbm(42, x, y, ::simplex, octaves, lacunarity, gain)
                    if (map[i] > max) max = map[i]
                    else if (map[i] < min) min = map[i]
                }
                /*indices.forEach { i ->
                    map[i] -= min
                    map[i] /= max - min
                    map[i] *= 2.0
                    map[i] -= 1.0
                }*/

                var prevH = 0.0
                var prevShadow = false

                indices.forEach { i ->
                    val h = map[i]

                    var shadow = 0.0

                    var rayH = if (h < 0.0) 0.0 else h
                    val startX = i % MAP_SIZE
                    var rayX = startX
                    var rayY = (i - startX) / MAP_SIZE

                    if (rayH < prevH || prevShadow) {
                        while (rayX > 0 && rayH < 1.0 && shadow != 1.0) {
                            rayX -= 1
                            rayH += deltaH
                            val diff = map[rayY * MAP_SIZE + rayX] - rayH
                            if (diff > 0.0) {
                                // in shade
                                shadow = 1.0
                                prevShadow = true
                            } else if (diff > -deltaH) {
                                shadow = diff / -deltaH
                            }
                        }
                        if (shadow > 0.0 && shadow < 1.0) {
                            // trace back lightRay
                            while (rayX != startX && rayH > map[rayY * MAP_SIZE + rayX]) {
                                lightRays[rayY * MAP_SIZE + rayX] = rayH
                                    .coerceAtLeast(lightRays[rayY * MAP_SIZE + rayX])
                                    .coerceAtLeast(map[rayY * MAP_SIZE + rayX])
                                rayH -= deltaH
                                rayX += 1
                            }
                        } else if (shadow == 0.0) {
                            lightRays[rayY * MAP_SIZE + startX] = h
                        }
                    }
                    prevShadow = shadow == 1.0 && startX != MAP_SIZE - 1
                    prevH = h
                    shadows[i] = shadow
                }

                indices.forEach { i ->
                    val h = map[i]


                    var x = i % MAP_SIZE
                    var y = i / MAP_SIZE
                    var s01 = if (x > 0) map[i - 1] else h
                    var s21 = if (x < MAP_SIZE - 1) map[i + 1] else h
                    var s10 = if (y > 0) map[i - MAP_SIZE] else h
                    var s12 = if (y < MAP_SIZE - 1) map[i + MAP_SIZE] else h
                    val va = Vector3(normalScale, 0.0, s21 - s01).normalized
                    val vb = Vector3(0.0, normalScale, s12 - s10).normalized
                    val normal = va.cross(vb).normalized


                    var color = ColorRGBa.WHITE

                    var shade = 0.5

                    val shadow = shadows[i]

                    if (h < 0.0) {
                        shade = 0.7
                        color = ColorRGBa.BLUE.mix(color, 0.1 + simplex(13, i * 1.0) * 0.2)
                    } else {
                        val slope = 1.0 - normal.dot(Vector3.UNIT_Z)
                        val fertility = (1.0 - slope * 0.9) * (1.0 - h)
                        if (fertility > 0.2) {
                            if (h < 0.05) color = ColorRGBa.LIGHT_YELLOW
                            else color = ColorRGBa.DARK_GREEN
                        } else color = ColorRGBa.KHAKI
                        color = ColorRGBa.WHITE

                        shade += max(normal.dot(sunDir), 0.0) * 0.5
                    }

                    shade -= 0.3 * shadow

                    colors[i] = color.shade(shade)
                }

                indices.forEach { i ->
                    val h = map[i]
                    val lightRay = lightRays[i]
                    var fog = 0.0
                    //fog += Math.E.pow(-3 * lightRay - 2)
                    //fog += Math.E.pow(-3 * h.coerceAtLeast(0.0) - 1.5)


                    colors[i] = colors[i]?.mix(ColorRGBa.LIGHT_SKY_BLUE, fog)
                }

                val end = System.currentTimeMillis()
                println("Thread ${Thread.currentThread().name} finished at ${end - start} ms, took ${end - threadStart} ms")

            }
            }


        }


        var camX = -RECT_SIZE * MAP_SIZE / 2.0
        var camY = -RECT_SIZE * MAP_SIZE / 2.0
        var zoom = 0.2

        var fps = 0

        extend {
            if (frameCount % 10 == 0) fps = (1.0 / deltaTime).toInt()

            drawer.fontMap = font

            keyboard.pressedKeys.let {
                val move = 2.0 / zoom
                camX += if (it.contains("a")) move else 0.0;
                camX -= if (it.contains("d")) move else 0.0;
                camY += if (it.contains("w")) move else 0.0;
                camY -= if (it.contains("s")) move else 0.0;
                zoom *= if (it.contains("e")) 1.1 else 1.0;
                zoom /= if (it.contains("q")) 1.1 else 1.0
            }

            drawer.isolated {
                drawer.translate(width / 2.0, height / 2.0)
                drawer.scale(zoom)
                drawer.translate(camX, camY)
                drawer.clear(ColorRGBa.WHITE)

                drawer.rectangles {
                    stroke = null
                    repeat(MAP_SIZE * MAP_SIZE) { i ->
                        val x = i % MAP_SIZE * RECT_SIZE
                        val y = i / MAP_SIZE * RECT_SIZE
                        fill = colors[i]
                        rectangle(x, y, RECT_SIZE, RECT_SIZE)
                    }
                }
            }

            drawer.fill = ColorRGBa.BLACK
            drawer.text("$fps fps", 10.0, 10.0)


        }

        mouse.scrolled.listen {
            zoom += it.dragDisplacement.y
        }

    }
}

