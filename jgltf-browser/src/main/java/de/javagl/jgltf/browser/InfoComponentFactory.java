/*
 * www.javagl.de - JglTF
 *
 * Copyright 2015-2016 Marco Hutter - http://www.javagl.de
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
package de.javagl.jgltf.browser;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.tree.TreePath;

import de.javagl.jgltf.impl.v1.GlTF;
import de.javagl.jgltf.model.GltfConstants;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.io.Buffers;
import de.javagl.jgltf.model.io.GltfWriter;
import de.javagl.jgltf.model.v1.GltfModelV1;
import de.javagl.swing.tasks.SwingTask;
import de.javagl.swing.tasks.SwingTaskExecutors;

/**
 * A class for creating components that display information about 
 * glTF elements
 */
class InfoComponentFactory
{
    /**
     * The logger used in this class
     */
    private static final Logger logger = 
        Logger.getLogger(InfoComponentFactory.class.getName());
    
    /**
     * The {@link GltfModel} that the information will be obtained from
     */
    private final GltfModel gltfModel;
    
    /**
     * The FileChooser for saving files
     */
    private final JFileChooser saveFileChooser;
    
    /**
     * Create a factory for creating info components for the elements
     * that appear in the given {@link GltfModel}
     * 
     * @param gltfModel The backing {@link GltfModel}
     */
    InfoComponentFactory(GltfModel gltfModel)
    {
        this.gltfModel = gltfModel;
        this.saveFileChooser = new JFileChooser();
    }
    
    /**
     * Create the info component for the given tree selection path
     * 
     * @param selectionPath The tree selection path
     * @return The info component (may be <code>null</code>)
     */
    JComponent createInfoComponent(TreePath selectionPath)
    {
        if (selectionPath == null)
        {
            return null;
        }
        Object selectedValue = ObjectTrees.getNodeEntryValue(selectionPath);
        String pathString = ObjectTrees.createPathString(selectionPath);

        boolean showLog = false;
        showLog = true;
        if (showLog)
        {
            // Avoid printing a whole data URI in the log
            String selectedValueString = String.valueOf(selectedValue);
            if (selectedValueString.length() > 100)
            {
                selectedValueString = 
                    selectedValueString.substring(0, 100) + "...";
            }
            logger.fine("selected      "+selectedValueString);
            logger.fine("pathString is "+pathString);
        }
        
        if (pathString.equals("glTF"))
        {
            return createJsonInfoComponent();
        }
        
        // Check if the selected path is a GL constant. In this case, 
        // just display the string representation of the GL constant
        Set<String> glConstantPaths = new LinkedHashSet<String>();
        glConstantPaths.add("glTF.shaders.*.type");
        glConstantPaths.add("glTF.accessors.*.componentType");
        glConstantPaths.add("glTF.techniques.*.parameters.*.type");
        glConstantPaths.add("glTF.techniques.*.states.enable.*");
        glConstantPaths.add("glTF.textures.*.format");
        glConstantPaths.add("glTF.textures.*.internalFormat");
        glConstantPaths.add("glTF.textures.*.target");
        glConstantPaths.add("glTF.textures.*.type");
        glConstantPaths.add("glTF.bufferViews.*.target");
        for (String glConstantPath : glConstantPaths)
        {
            if (RegEx.matches(pathString, glConstantPath))
            {
                if (selectedValue instanceof Number)
                {
                    Number glConstant = (Number)selectedValue;
                    String glConstantString = 
                        GltfConstants.stringFor(glConstant.intValue());
                    return createMessageInfoPanel(
                        "GL constant: " + glConstantString);
                }
                return createMessageInfoPanel(
                    "Unexpected entry: " + selectedValue);
            }
        }
        
        return createGenericInfoComponent(selectedValue);
    }
    
    /**
     * Creates an info component that shows the JSON string representation
     * of the {@link GlTF} that was given in the constructor, or allows 
     * creating the JSON representation of the current {@link GlTF} if
     * the given one was <code>null</code>.
     * 
     * @return The info component
     */
    private JComponent createJsonInfoComponent()
    {
        JPanel jsonInfoPanel = new JPanel(new FlowLayout());
        JButton createButton = new JButton("Create JSON...");
        createButton.addActionListener( e -> 
        {
            createButton.setText("Creating JSON...");
            createButton.setEnabled(false);
            createJson(jsonInfoPanel);
        });
        jsonInfoPanel.add(createButton);
        return jsonInfoPanel;
    }
    
    /**
     * Start a background task to create the component containing the JSON 
     * representation of the current {@link GlTF}, and place it into the 
     * given panel.
     * 
     * @param jsonInfoPanel The target panel
     */
    private void createJson(JPanel jsonInfoPanel)
    {
        Object gltf = gltfModel.getGltf();
        SwingTask<String, ?> swingTask = new SwingTask<String, Void>()
        {
            @Override
            protected String doInBackground() throws Exception
            {
                String createdJsonString = createJsonString(gltf);
                return createdJsonString;
            }
        };
        swingTask.addDoneCallback(task -> 
        {
            try
            {
                String createdJsonString = task.get();
                JComponent textInfoPanel = 
                    createTextInfoPanel("JSON:", createdJsonString);
                jsonInfoPanel.removeAll();
                jsonInfoPanel.setLayout(new GridLayout(1,1));
                jsonInfoPanel.add(textInfoPanel);
                jsonInfoPanel.revalidate();
            } 
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            catch (ExecutionException e)
            {
                jsonInfoPanel.removeAll();
                jsonInfoPanel.setLayout(new GridLayout(1,1));
                JTextArea textArea = new JTextArea();
                textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
                jsonInfoPanel.add(new JScrollPane(textArea));
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                textArea.setText(sw.toString());
                jsonInfoPanel.revalidate();
            }
        });
        SwingTaskExecutors.create(swingTask)
            .build()
            .execute();            
    }

    /**
     * Creates a JSON string representation of the given glTF. This string
     * may contain only an error message if creating the actual JSON string
     * caused an exception.
     * 
     * @param gltf The glTF object
     * @return The The JSON string
     */
    private static String createJsonString(Object gltf)
    {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            GltfWriter gltfWriter = new GltfWriter();
            gltfWriter.write(gltf, baos);
            return new String(baos.toByteArray());
        }
        catch (IOException e)
        {
            return e.getMessage();
        }
    }
    

    /**
     * Create an info component for the given selected object, depending
     * on its type. May return <code>null</code> if the given object 
     * has a type for which no info component exists
     * 
     * @param selectedValue The selected value
     * @return The info component, or <code>null</code>
     */
    private JComponent createGenericInfoComponent(Object selectedValue)
    {
        if (gltfModel instanceof GltfModelV1)
        {
            GltfModelV1 gltfModelV1 = (GltfModelV1)gltfModel;
            InfoComponentFactoryV1 infoComponentFactory =
                new InfoComponentFactoryV1(this, gltfModelV1);
            return infoComponentFactory.createGenericInfoComponent(
                selectedValue);
        }
        
        // TODO Implement info components for GltfModelV2
        logger.warning("GltfModelV2 is not supported yet");
        
        return null;
    }


    /**
     * Returns the contents of the given buffer as a <code>BufferedImage</code>,
     * or <code>null</code> if the given buffer is <code>null</code>, or
     * the data can not be converted into a buffered image.
     * 
     * @param byteBuffer The byte buffer
     * @return The buffered image
     */
    static BufferedImage readAsBufferedImage(ByteBuffer byteBuffer)
    {
        if (byteBuffer == null)
        {
            return null;
        }
        try (InputStream inputStream = 
            Buffers.createByteBufferInputStream(byteBuffer.slice()))
        {
            return ImageIO.read(inputStream);
        }
        catch (IOException e)
        {
            return null;
        }
    }
    
    /**
     * Create an info component with the given message
     * 
     * @param string The message string
     * @return The component
     */
    JComponent createMessageInfoPanel(String string)
    {
        JPanel messageInfoPanel = new JPanel(new FlowLayout());
        messageInfoPanel.add(new JLabel(string));
        return messageInfoPanel;
    }

    /**
     * Create an info component with the given image
     * 
     * @param image The image
     * @return The component
     */
    JComponent createImageInfoPanel(BufferedImage image)
    {
        JPanel imageInfoPanel = new JPanel(new BorderLayout());
        
        JPanel controlPanel = new JPanel(new FlowLayout());
        controlPanel.add(new JLabel("Image:"));
        
        JButton saveButton = new JButton("Save as PNG...");
        saveButton.addActionListener(
            e -> saveImageAs(imageInfoPanel, image));
        controlPanel.add(saveButton);
        
        imageInfoPanel.add(controlPanel, BorderLayout.NORTH);
        imageInfoPanel.add(new JScrollPane(
            new JLabel(new ImageIcon(image))), BorderLayout.CENTER);
        return imageInfoPanel;
    }
    
    /**
     * Open the save file chooser offering the option to save the given
     * image as a file
     * 
     * @param parent The parent component
     * @param image The image
     */
    private void saveImageAs(JComponent parent, BufferedImage image)
    {
        int option = saveFileChooser.showSaveDialog(parent);
        if (option == JFileChooser.APPROVE_OPTION)
        {
            File file = saveFileChooser.getSelectedFile();
            if (file.exists())
            {
                int overwriteOption = 
                    JOptionPane.showConfirmDialog(
                        parent, "File exists. Overwrite?");
                if (overwriteOption == JOptionPane.CANCEL_OPTION)
                {
                    saveImageAs(parent, image);
                    return;
                }
                if (overwriteOption != JOptionPane.YES_OPTION)
                {
                    return;
                }
            }
            try
            {
                ImageIO.write(image, "png", file);
            }
            catch (IOException e)
            {
                JOptionPane.showMessageDialog(parent, 
                    "Error while writing file: "+e.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
                e.printStackTrace();
            }
        }
    }
    
    
    /**
     * Create an info component with the given text
     * 
     * @param title The title for the component
     * @param text The text
     * @return The component
     */
    JComponent createTextInfoPanel(String title, String text)
    {
        JPanel textInfoPanel = new JPanel(new BorderLayout());
        
        JPanel controlPanel = new JPanel(new FlowLayout());
        controlPanel.add(new JLabel(title));
        
        JButton saveButton = new JButton("Save as...");
        saveButton.addActionListener(
            e -> saveTextAs(textInfoPanel, text));
        controlPanel.add(saveButton);
        
        textInfoPanel.add(controlPanel, BorderLayout.NORTH);
        JTextArea textArea = new JTextArea(text);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textInfoPanel.add(new JScrollPane(textArea), BorderLayout.CENTER);
        return textInfoPanel;
    }

    /**
     * Open the save file chooser offering the option to save the given
     * text as a file
     * 
     * @param parent The parent component
     * @param text The text
     */
    private void saveTextAs(JComponent parent, String text)
    {
        int option = saveFileChooser.showSaveDialog(parent);
        if (option == JFileChooser.APPROVE_OPTION)
        {
            File file = saveFileChooser.getSelectedFile();
            if (file.exists())
            {
                int overwriteOption = 
                    JOptionPane.showConfirmDialog(
                        parent, "File exists. Overwrite?");
                if (overwriteOption == JOptionPane.CANCEL_OPTION)
                {
                    saveTextAs(parent, text);
                    return;
                }
                if (overwriteOption != JOptionPane.YES_OPTION)
                {
                    return;
                }
            }
            
            try (FileWriter fileWriter = new FileWriter(file))
            {
                fileWriter.write(text);
            }
            catch (IOException e)
            {
                JOptionPane.showMessageDialog(parent, 
                    "Error while writing file: "+e.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
                e.printStackTrace();
            }
        }
    }

    
}
