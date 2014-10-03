package net.koofr.play2sprites

import sbt._
import Keys._
import java.io.IOException
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

case class SpriteInfo(file: File, width: Int, height: Int, offsetY: Int, cssClass: String)

class GenerateSprites(prefix: String) extends Plugin {
  val pfx = if (prefix.isEmpty) "" else prefix + "-"

  val spritesSrcImages = SettingKey[PathFinder](
    pfx + "sprites-src-images",
    "source images for sprites"
  )

  val spritesDestImage = SettingKey[File](
    pfx + "sprites-dest-image",
    "destination sprite image file"
  )

  val spritesImagePadding = SettingKey[Int](
    pfx + "sprites-image-padding",
    "spacing between sprite images"
  )

  val spritesCssSpritePath = SettingKey[String](
    pfx + "sprites-css-sprite-path",
    "path to sprite image relative to css file"
  )

  val spritesCssClassPrefix = SettingKey[String](
    pfx + "sprites-css-class-prefix",
    "css class prefix"
  )

  val spritesDestCss = SettingKey[File](
    pfx + "sprites-dest-css",
    "destination css file"
  )

  val spritesGen = TaskKey[Seq[File]](
    pfx + "sprites-gen",
    "generate sprite from images"
  )

  val genSpritesSettings: Seq[Setting[_]] = Seq(

    spritesCssClassPrefix := "",

    spritesGen <<= (
      spritesSrcImages,
      spritesDestImage,
      spritesImagePadding,
      spritesCssSpritePath,
      spritesCssClassPrefix,
      spritesDestCss,
      cacheDirectory,
      streams
    ) map { (srcImages, destImage, spritesImagePadding, relPath, cssClassPrefix, css, cache, s) =>
        val files = srcImages.get.sortBy(_.getName)

        val cacheFile = cache / (pfx + "sprites")
        val currentInfos = files.map(f => f -> FileInfo.lastModified(f)).toMap

        val (previousRelation, previousInfo) = Sync.readInfo(cacheFile)(FileInfo.lastModified.format)

        if (!files.isEmpty && (previousInfo != currentInfos || !destImage.exists || !css.exists)) {
          val generated = generateSprites(files, destImage, spritesImagePadding, relPath, cssClassPrefix, css, s)

          Sync.writeInfo(cacheFile,
            Relation.empty[File, File] ++ generated,
            currentInfos)(FileInfo.lastModified.format)
        }

        Seq()
      }

  )

  def generateSprites(files: Seq[File], destImage: File, imagePadding: Int, relPath: String,
    cssClassPrefix: String, css: File, s: TaskStreams) = {

    s.log.info("Generating sprites for %d images" format (files.length))

    IO.createDirectory(destImage.getParentFile)
    IO.createDirectory(css.getParentFile)

    val eitherImages: Seq[(File, Either[Exception, BufferedImage])] = files.map { file =>
      try {
        file -> Option(ImageIO.read(file)).map(Right(_)).getOrElse(Left(new NullPointerException))
      } catch {
        case e: IOException => file -> Left(e)
      }
    }

    val images = eitherImages.collect { case (file, Right(img)) => file -> img }

    val errors = eitherImages.collect { case (file, Left(e)) => file -> e }

    errors map {
      case (file, error) =>
        s.log.error("Image %s could not be loaded: %s." format (file, error))
    }

    val width = images.map(_._2.getWidth).max
    val height = images.map(_._2.getHeight).sum

    val sprite = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    val processed = images.foldLeft((List[SpriteInfo](), 0)) {
      case ((processed, y), (file, img)) =>
        sprite.createGraphics().drawImage(img, 0, y, null)

        val cleanName = file.getName.toLowerCase.split("\\.").init.mkString("-").replace("_", "-")

        val cssClass = "." + cssClassPrefix + cleanName

        val info = SpriteInfo(file, img.getWidth, img.getHeight, y + imagePadding, cssClass)

        (info :: processed, y + info.height + imagePadding)
    }._1.reverse

    val written = ImageIO.write(sprite, "png", destImage)

    val cssClassBodies = processed.map { info =>
      val css = """|%s {
        |  background-position: 0 -%dpx;
        |  width: %dpx;
        |  height: %dpx;
        |}""".stripMargin

      css.format(info.cssClass, info.offsetY, info.width, info.height)
    }.mkString("\n\n")

    val cssOutputTpl = """|%s {
      |  background: url('%s') no-repeat;
      |}
      |
      |%s""".stripMargin

    val allCssClasses = processed.map(_.cssClass).mkString(",\n")

    val cssOutput = cssOutputTpl format (allCssClasses, relPath, cssClassBodies)

    IO.write(css, cssOutput)

    files.map(_ -> destImage) ++ files.map(_ -> css)
  }

}

object GenerateSprites extends GenerateSprites("")
